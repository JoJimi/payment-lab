package org.example.cs_study.order.listener;

import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.common.outbox.OutboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.InventoryReservedPayload;
import org.example.cs_study.event.payload.NotificationRequestedPayload;
import org.example.cs_study.event.payload.NotificationType;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 정상 흐름의 세 번째 이음매(2.12) — inventory-service가 재고 예약(+확정)에 성공하면
 * Saga를 NOTIFICATION 단계로 넘기고 {@code notification.requested}를 발행한다. 이 이벤트가
 * 나가면(Outbox에 적재되면) Saga 입장에서 남은 정상 흐름 단계가 없다 — 완료 처리
 * ({@code sagaInstance.complete()})는 2.13에서 보상 흐름과 함께 정리한다(알림 자체는
 * 실패해도 보상하지 않는 것이 로드맵 방침이라, "Saga가 언제 COMPLETED로 확정되는가"는
 * 보상 경계 설계와 묶어서 다루는 게 맞다).
 */
@Component
public class InventoryReservedListener {

    private final InboxService inboxService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;

    public InventoryReservedListener(
            InboxService inboxService,
            OutboxService outboxService,
            ObjectMapper objectMapper,
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository) {
        this.inboxService = inboxService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
    }

    @KafkaListener(topics = "inventory.reserved")
    public void onMessage(String message) {
        EventEnvelope<InventoryReservedPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, InventoryReservedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> handle(envelope.payload()));
        }
    }

    private void handle(InventoryReservedPayload payload) {
        SagaInstance sagaInstance = sagaInstanceRepository
                .findByOrderId(payload.orderId())
                .orElseThrow(() -> new IllegalStateException("Saga 인스턴스를 찾을 수 없습니다: orderId=" + payload.orderId()));
        // order-service는 inventory-service에 별도 커맨드를 보내지 않는다(위 클래스 Javadoc) —
        // 재고 예약은 inventory-service가 payment.completed를 직접 구독해 자율적으로 수행한
        // 결과다. 그래서 이 스텝은 order-service 입장에서 "요청"이 따로 없고, inventory.reserved
        // 수신이 곧 이 스텝의 존재를 아는 첫 순간이다 — PENDING을 거치지 않고 바로 성공으로 기록한다.
        SagaStep inventoryStep = sagaStepRepository
                .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.INVENTORY)
                .orElseGet(() -> new SagaStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY, null));
        inventoryStep.succeed(objectMapper.writeValueAsString(payload));
        sagaStepRepository.save(inventoryStep);

        sagaInstance.advanceTo(SagaStepName.NOTIFICATION);
        sagaInstanceRepository.save(sagaInstance);

        NotificationRequestedPayload notificationPayload = new NotificationRequestedPayload(
                payload.orderId(), NotificationType.ORDER_COMPLETED, "주문이 완료됐습니다: orderId=" + payload.orderId());
        sagaStepRepository.save(new SagaStep(
                sagaInstance.getSagaId(), SagaStepName.NOTIFICATION, objectMapper.writeValueAsString(notificationPayload)));
        outboxService.save(
                EventType.NOTIFICATION_REQUESTED, "Order", payload.orderId().toString(), notificationPayload);
    }
}
