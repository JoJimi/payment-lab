package org.example.cs_study.payment;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.example.cs_study.common.idempotency.Idempotent;
import org.example.cs_study.common.order.OrderPort;
import org.example.cs_study.common.order.OrderView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderPort orderPort;
    private final MockPgClient mockPgClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderPort orderPort,
            MockPgClient mockPgClient,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.orderPort = orderPort;
        this.mockPgClient = mockPgClient;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 멱등 키 검증이 비즈니스 로직보다 먼저 수행된다 — {@link Idempotent} AOP가 이 메서드
     * 진입 자체를 가로챈다(부록 A-1의 2단 방어). 동일 키 재요청은 이 바디를 다시 실행하지
     * 않고 저장된 원본 응답을 그대로 재현한다 (1.8, 1.9).
     *
     * <p><b>트랜잭션을 세 구간으로 나눈다</b> (.coderabbit.yaml payment 경로 지침: 외부 PG 호출이
     * {@code @Transactional} 범위 안에서 일어나면 안 된다 — 외부 I/O가 DB 커넥션을 점유한 채
     * 대기하면 부하 상황에서 커넥션 풀이 고갈된다):
     * <ol>
     *   <li>짧은 트랜잭션: 주문 검증 + PENDING 결제 저장</li>
     *   <li>트랜잭션 밖: Mock PG 호출 (커넥션을 점유하지 않는다)</li>
     *   <li>짧은 트랜잭션: 결과 반영 (+ 승인 시 주문을 PAID로 전이)</li>
     * </ol>
     * 이 메서드 자체는 더 이상 {@code @Transactional}이 아니다 — 통짜 트랜잭션으로 묶으면
     * 위 지침을 어기게 된다.
     */
    @Idempotent(key = "#idempotencyKey")
    public PaymentResponse requestPayment(String idempotencyKey, RequestPaymentRequest request) {
        OrderView order = orderPort.findOrder(request.orderId());
        validateOrder(order, request);

        Long paymentId = savePending(idempotencyKey, request);

        MockPgResult result = mockPgClient.requestPayment(idempotencyKey, request.amount(), request.currency());

        return applyResult(paymentId, request.orderId(), result);
    }

    private void validateOrder(OrderView order, RequestPaymentRequest request) {
        if (order.paid()) {
            throw new PaymentOrderMismatchException("이미 결제 완료된 주문입니다: orderId=" + order.orderId());
        }
        if (bigDecimalNotEqual(order.totalAmount(), request.amount())) {
            throw new PaymentOrderMismatchException(
                    "결제 요청 금액이 주문 금액과 다릅니다: orderId=%s, 주문 금액=%s, 요청 금액=%s"
                            .formatted(order.orderId(), order.totalAmount(), request.amount()));
        }
        if (!order.currency().equals(request.currency())) {
            throw new PaymentOrderMismatchException(
                    "결제 요청 통화가 주문 통화와 다릅니다: orderId=%s, 주문 통화=%s, 요청 통화=%s"
                            .formatted(order.orderId(), order.currency(), request.currency()));
        }
    }

    private static boolean bigDecimalNotEqual(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) != 0;
    }

    private Long savePending(String idempotencyKey, RequestPaymentRequest request) {
        return transactionTemplate.execute(status -> {
            Payment payment = new Payment(request.orderId(), idempotencyKey, request.amount(), request.currency());
            paymentRepository.save(payment);
            return payment.getId();
        });
    }

    private PaymentResponse applyResult(Long paymentId, Long orderId, MockPgResult result) {
        return transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
            switch (result.outcome()) {
                case APPROVED -> {
                    payment.approve(result.transactionId());
                    orderPort.markPaid(orderId); // 결제 승인과 같은 트랜잭션에서 커밋한다.
                }
                case FAILED -> payment.fail();
                case TIMEOUT -> payment.markUnknown();
            }
            // 1.19: 결제 성공/실패(+UNKNOWN) 카운터. status 태그로 나눠 Grafana에서 비율을 본다.
            meterRegistry.counter("payment.result", "status", payment.getStatus().name()).increment();
            return PaymentResponse.from(payment);
        });
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return PaymentResponse.from(payment);
    }

    @Transactional
    public PaymentResponse cancel(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        payment.cancel();
        return PaymentResponse.from(payment);
    }
}
