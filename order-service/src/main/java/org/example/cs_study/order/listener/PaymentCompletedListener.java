package org.example.cs_study.order.listener;

import org.example.cs_study.common.exception.order.OrderNotFoundException;
import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.PaymentCompletedPayload;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.repository.OrderRepository;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 정상 흐름의 두 번째 이음매(2.12) — payment-service가 결제를 승인하면 주문을 PAID로
 * 전이시키고 Saga를 INVENTORY 단계로 넘긴다. inventory-service에 별도로 "재고 예약해라"
 * 커맨드를 보내지 않는다 — inventory-service가 이 토픽({@code payment.completed})을 직접
 * 구독해 스스로 반응한다(docs/architecture/event-catalog.md). 이 리스너는 오케스트레이터
 * 자신의 상태(주문/Saga)만 갱신하면 된다.
 *
 * <p><b>Saga가 이미 STARTED를 벗어났으면 조용히 무시하는 이유(2.17, 장애 주입 테스트로
 * 발견)</b>: {@link org.example.cs_study.order.scheduler.SagaTimeoutScheduler}(2.15)가
 * PAYMENT 응답이 안 온다고 판단해 이 Saga를 먼저 회수(주문 CANCELLED)해버린 뒤, 뒤늦게
 * payment-service가 복구돼 이 이벤트가 도착하는 레이스가 실제로 가능하다 — 이때 가드 없이
 * {@code order.markPaid()}를 부르면 {@code CANCELLED → PAID}는 허용되지 않는 전이라
 * {@code InvalidStateTransitionException}이 나고, 재시도 3회(2.16)를 소진한 뒤
 * {@code payment.completed-dlt}에 쌓인다. {@link InventoryReservedListener}/
 * {@link PaymentFailedListener}/{@link InventoryFailedListener}가 이미 쓰던 것과 같은
 * 상태 가드를 여기도 추가했다 — 예외로 DLQ까지 가는 대신 조용히 무시한다(2.14의
 * "보상 자체의 멱등성"과 같은 원리, 방향만 정상 흐름 쪽). 이 이벤트가 나타내는 "실제로는
 * 결제가 성공했을 수 있다"는 사실 자체를 해소하는 건 이 가드의 몫이 아니다 — 그건 이슈
 * #72(PAYMENT/UNKNOWN 실제 재조회)가 다룬다.
 */
@Component
public class PaymentCompletedListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;

    public PaymentCompletedListener(
            InboxService inboxService,
            ObjectMapper objectMapper,
            OrderRepository orderRepository,
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository) {
        this.inboxService = inboxService;
        this.objectMapper = objectMapper;
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
    }

    @KafkaListener(topics = "payment.completed")
    public void onMessage(String message) {
        EventEnvelope<PaymentCompletedPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, PaymentCompletedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> handle(envelope.payload()));
        }
    }

    private void handle(PaymentCompletedPayload payload) {
        SagaInstance sagaInstance = sagaInstanceRepository
                .findByOrderId(payload.orderId())
                .orElseThrow(() -> new IllegalStateException("Saga 인스턴스를 찾을 수 없습니다: orderId=" + payload.orderId()));
        if (sagaInstance.getStatus() != SagaStatus.STARTED) {
            // 클래스 Javadoc 참고 — 타임아웃이 먼저 이 Saga를 끝내버린 뒤 뒤늦게 도착한
            // 성공 응답이다. 주문/스텝 상태를 건드리지 않고 그대로 둔다.
            return;
        }

        Order order = orderRepository.findById(payload.orderId()).orElseThrow(() -> new OrderNotFoundException(payload.orderId()));
        order.markPaid();
        orderRepository.save(order);

        SagaStep paymentStep = sagaStepRepository
                .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                .orElseThrow(() -> new IllegalStateException("PAYMENT 스텝을 찾을 수 없습니다: sagaId=" + sagaInstance.getSagaId()));
        paymentStep.succeed(objectMapper.writeValueAsString(payload));
        sagaStepRepository.save(paymentStep);

        sagaInstance.advanceTo(SagaStepName.INVENTORY);
        sagaInstanceRepository.save(sagaInstance);
    }
}
