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
 * Saga 정상 흐름의 세 번째이자 마지막 이음매(2.12/2.13) — inventory-service가 재고
 * 예약(+확정)에 성공하면 Saga를 NOTIFICATION 단계로 넘기고 {@code notification.requested}를
 * 발행한 뒤, 그 자리에서 바로 Saga를 완료(COMPLETED) 처리한다.
 *
 * <p><b>알림 전달을 기다리지 않고 바로 완료하는 이유(2.13에서 확정)</b>: 알림 실패는 보상
 * 대상이 아니다(로드맵 방침) — notification-service가 실제로 알림을 보냈는지 확인하는
 * 이벤트 자체가 카탈로그에 없고, 있어도 Saga가 기다릴 이유가 없다(기다렸다 실패하면 뭘 할
 * 것인가? 보상하지 않기로 했으니 할 일이 없다). 그래서 "발송 지시를 Outbox에 적재했다"를
 * NOTIFICATION 스텝의 성공이자 Saga 전체의 완료로 본다 — INVENTORY 스텝(reserve+confirm)을
 * 같은 트랜잭션에서 바로 확정 처리한 2.12의 설계와 같은 논리다.
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

        NotificationRequestedPayload notificationPayload = new NotificationRequestedPayload(
                payload.orderId(), NotificationType.ORDER_COMPLETED, "주문이 완료됐습니다: orderId=" + payload.orderId());
        String notificationJson = objectMapper.writeValueAsString(notificationPayload);
        SagaStep notificationStep = new SagaStep(sagaInstance.getSagaId(), SagaStepName.NOTIFICATION, notificationJson);
        // 클래스 Javadoc 참고 — 알림 전달 확인 이벤트가 없어 Outbox 적재 자체를 성공으로 본다.
        notificationStep.succeed(notificationJson);
        sagaStepRepository.save(notificationStep);
        outboxService.save(
                EventType.NOTIFICATION_REQUESTED, "Order", payload.orderId().toString(), notificationPayload);

        sagaInstance.complete();
        sagaInstanceRepository.save(sagaInstance);
    }
}
