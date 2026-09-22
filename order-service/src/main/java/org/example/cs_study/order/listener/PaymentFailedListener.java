package org.example.cs_study.order.listener;

import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.PaymentFailedPayload;
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
 * 보상 트랜잭션의 첫 번째 트리거(2.13) — 결제가 거절/오류로 실패하면 PAYMENT 스텝은 한 번도
 * 성공한 적이 없다(부록: {@code SagaStepStatus.FAILED}는 되돌릴 부수효과가 없다는 뜻). 되돌릴
 * 것 없이 곧장 주문을 취소한다. {@link SagaCompensationService#finish}가 주문 취소부터 Saga
 * 종료까지 마무리한다.
 */
@Component
public class PaymentFailedListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final SagaCompensationService compensationService;

    public PaymentFailedListener(
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

    @KafkaListener(topics = "payment.failed")
    public void onMessage(String message) {
        EventEnvelope<PaymentFailedPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, PaymentFailedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> handle(envelope.payload()));
        }
    }

    private void handle(PaymentFailedPayload payload) {
        SagaInstance sagaInstance = sagaInstanceRepository
                .findByOrderId(payload.orderId())
                .orElseThrow(() -> new IllegalStateException("Saga 인스턴스를 찾을 수 없습니다: orderId=" + payload.orderId()));
        if (sagaInstance.getStatus() != SagaStatus.STARTED) {
            // 보상 자체의 멱등성(2.14) — payment.failed가 서로 다른 eventId로 중복 발행되면
            // InboxService의 eventId 기반 중복 방지를 우회한다(같은 eventId 재전달은 이미
            // 막힘). 이 Saga가 이미 STARTED를 벗어났다면(다른 트리거로 보상이 시작됐거나
            // 끝났다는 뜻) 여기서 조용히 멈춘다 — 그대로 진행하면 이미 FAILED/COMPENSATED인
            // 스텝에 fail()을 다시 호출해 InvalidStateTransitionException이 난다.
            return;
        }
        SagaStep paymentStep = sagaStepRepository
                .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                .orElseThrow(() -> new IllegalStateException("PAYMENT 스텝을 찾을 수 없습니다: sagaId=" + sagaInstance.getSagaId()));
        paymentStep.fail(objectMapper.writeValueAsString(payload));
        sagaStepRepository.save(paymentStep);

        sagaInstance.beginCompensation();
        sagaInstanceRepository.save(sagaInstance);

        compensationService.finish(sagaInstance, payload.orderId(), payload.reason());
    }
}
