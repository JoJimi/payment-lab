package org.example.cs_study.order.listener;

import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.InventoryFailedPayload;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.example.cs_study.order.service.SagaCompensationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 보상 트랜잭션의 두 번째 트리거(2.13) — 재고 예약이 실패하면 이미 성공한 PAYMENT 스텝을
 * 보상(결제 취소) 대상으로 표시하고 주문을 취소한다. 실제 결제 취소는 {@code order.cancelled}를
 * 구독하는 payment-service가 수행한다({@link SagaCompensationService#finish}가 발행).
 *
 * <p>inventory-service는 예약 실패 시 아무것도 바꾸지 않는다({@code reserve()}가 재고 부족을
 * 확인하고 예외만 던진 뒤 끝난다, {@link org.example.cs_study.inventory.domain.Inventory#reserve}
 * 참고) — 그래서 order-service는 재고를 "해제"하는 이벤트를 따로 발행하지 않는다(로드맵
 * "재고 예약 실패 → 결제 취소 → 주문 취소"와 정확히 일치, inventory-service는 {@code
 * order.cancelled}를 구독하지 않는다).
 *
 * <p>{@link InventoryReservedListener}와 마찬가지로 order-service는 이 스텝에 대해 따로
 * 커맨드를 보낸 적이 없다 — {@code inventory.failed} 수신이 곧 이 스텝의 존재를 아는 첫
 * 순간이라 PENDING을 거치지 않고 바로 실패로 기록한다.
 */
@Component
public class InventoryFailedListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final SagaCompensationService compensationService;

    public InventoryFailedListener(
            InboxService inboxService,
            ObjectMapper objectMapper,
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            SagaCompensationService compensationService) {
        this.inboxService = inboxService;
        this.objectMapper = objectMapper;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.compensationService = compensationService;
    }

    @KafkaListener(topics = "inventory.failed")
    public void onMessage(String message) {
        EventEnvelope<InventoryFailedPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, InventoryFailedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> handle(envelope.payload()));
        }
    }

    private void handle(InventoryFailedPayload payload) {
        SagaInstance sagaInstance = sagaInstanceRepository
                .findByOrderId(payload.orderId())
                .orElseThrow(() -> new IllegalStateException("Saga 인스턴스를 찾을 수 없습니다: orderId=" + payload.orderId()));
        if (sagaInstance.getStatus() != SagaStatus.STARTED) {
            // 보상 자체의 멱등성(2.14) — PaymentFailedListener의 같은 가드와 이유가 같다.
            return;
        }

        SagaStep inventoryStep = sagaStepRepository
                .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.INVENTORY)
                .orElseGet(() -> new SagaStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY, null));
        inventoryStep.fail(objectMapper.writeValueAsString(payload));
        sagaStepRepository.save(inventoryStep);

        SagaStep paymentStep = sagaStepRepository
                .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                .orElseThrow(() -> new IllegalStateException("PAYMENT 스텝을 찾을 수 없습니다: sagaId=" + sagaInstance.getSagaId()));
        // 결제 취소 자체는 order.cancelled를 구독하는 payment-service가 실제로 수행한다 —
        // 여기서는 "이 스텝을 보상 대상으로 표시"만 한다(낙관적 기록, 정상 흐름에서
        // inventory.reserved를 받고 바로 성공으로 기록하는 것과 대칭). payment-service 쪽
        // 처리를 확인하는 이벤트는 카탈로그에 없다.
        paymentStep.compensate();
        sagaStepRepository.save(paymentStep);

        sagaInstance.beginCompensation();
        sagaInstanceRepository.save(sagaInstance);

        compensationService.finish(sagaInstance, payload.orderId(), payload.reason());
    }
}
