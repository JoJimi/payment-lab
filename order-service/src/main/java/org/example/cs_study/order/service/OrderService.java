package org.example.cs_study.order.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.example.cs_study.common.exception.order.OrderNotFoundException;
import org.example.cs_study.common.outbox.OutboxService;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.OrderCreatedPayload;
import org.example.cs_study.event.payload.PaymentRequestedPayload;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.dto.request.CreateOrderRequest;
import org.example.cs_study.order.dto.response.OrderResponse;
import org.example.cs_study.order.repository.OrderRepository;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 2.12 — Saga 정상 흐름의 출발점. 주문 저장, Saga 시작, {@code order.created}/
 * {@code payment.requested} 발행을 한 트랜잭션에 묶는다(Outbox 패턴, 부록 A-3) — 셋 중
 * 하나라도 따로 커밋되면 "주문은 생겼는데 결제 지시가 안 나갔다" 같은 반쪽 상태가 된다.
 *
 * <p>이후 단계(결제 완료→재고 예약→알림)는 이 클래스가 아니라 각 {@code @KafkaListener}가
 * 진행시킨다 — {@link org.example.cs_study.order.listener} 패키지 참고.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final Duration sagaTimeout;

    public OrderService(
            OrderRepository orderRepository,
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            OutboxService outboxService,
            ObjectMapper objectMapper,
            // 2.15: 테스트가 짧은 타임아웃으로 SagaTimeoutScheduler를 검증할 수 있도록
            // 설정 가능하게 뺐다 — 실제 운영값은 기본 10분.
            @Value("${app.saga.timeout-minutes:10}") long sagaTimeoutMinutes) {
        // 음수가 설정되면 timeoutAt이 과거 시각이 돼 모든 신규 주문이 생성 직후 스케줄러에
        // 의해 조용히 취소된다(CodeRabbit 리뷰, PR #71) — 설정 오류를 기본값으로 덮지 않고
        // 즉시 기동을 막는다. 0은 테스트가 쓰므로 허용한다.
        if (sagaTimeoutMinutes < 0) {
            throw new IllegalArgumentException("app.saga.timeout-minutes는 0 이상이어야 합니다: " + sagaTimeoutMinutes);
        }
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.sagaTimeout = Duration.ofMinutes(sagaTimeoutMinutes);
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        BigDecimal totalAmount = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        Order order = new Order(request.productId(), request.quantity(), totalAmount, request.currency());
        order = orderRepository.save(order);

        SagaInstance sagaInstance = new SagaInstance(order.getId(), Instant.now().plus(sagaTimeout));
        sagaInstance.advanceTo(SagaStepName.PAYMENT);
        sagaInstance = sagaInstanceRepository.save(sagaInstance);

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequestedPayload paymentRequestedPayload =
                new PaymentRequestedPayload(order.getId(), totalAmount, request.currency(), idempotencyKey);
        sagaStepRepository.save(new SagaStep(
                sagaInstance.getSagaId(), SagaStepName.PAYMENT, objectMapper.writeValueAsString(paymentRequestedPayload)));

        outboxService.save(
                EventType.ORDER_CREATED,
                "Order",
                order.getId().toString(),
                new OrderCreatedPayload(order.getId(), order.getProductId(), order.getQuantity(), totalAmount, request.currency()));
        outboxService.save(EventType.PAYMENT_REQUESTED, "Order", order.getId().toString(), paymentRequestedPayload);

        return OrderResponse.from(order, sagaInstance.getStatus());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        SagaStatus sagaStatus =
                sagaInstanceRepository.findByOrderId(orderId).map(SagaInstance::getStatus).orElse(null);
        return OrderResponse.from(order, sagaStatus);
    }
}
