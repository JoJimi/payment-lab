package org.example.cs_study.payment.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.cs_study.common.exception.payment.PaymentNotFoundException;
import org.example.cs_study.common.exception.payment.PaymentOrderMismatchException;
import org.example.cs_study.common.idempotency.Idempotent;
import org.example.cs_study.payment.client.MockPgClient;
import org.example.cs_study.payment.client.MockPgResult;
import org.example.cs_study.payment.domain.Payment;
import org.example.cs_study.payment.dto.request.RequestPaymentRequest;
import org.example.cs_study.payment.dto.response.PaymentResponse;
import org.example.cs_study.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderValidator orderValidator;
    private final MockPgClient mockPgClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderValidator orderValidator,
            MockPgClient mockPgClient,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.orderValidator = orderValidator;
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
     *   <li>짧은 트랜잭션: PENDING 결제 저장</li>
     *   <li>트랜잭션 밖: Mock PG 호출 (커넥션을 점유하지 않는다)</li>
     *   <li>짧은 트랜잭션: 결과 반영</li>
     * </ol>
     * 이 메서드 자체는 더 이상 {@code @Transactional}이 아니다 — 통짜 트랜잭션으로 묶으면
     * 위 지침을 어기게 된다.
     *
     * <p><b>2.1/2.3:</b> 원래 이 메서드는 시작 시 {@code orderPort.findOrder()}로 주문을
     * 조회/검증하고, 승인 시 {@code orderPort.markPaid()}로 결제 승인과 같은 트랜잭션에서
     * 주문을 PAID로 전이시켰다. 멀티모듈 분리로 payment-service는 더 이상 order-service의
     * {@code OrderPort}를 (같은 JVM의) Java 인터페이스로 호출할 수 없어 두 호출을 모두 걷어냈다.
     *
     * <p>주문 검증은 {@link OrderValidator}로 옮겼다(지금은 {@link UnimplementedOrderValidator}
     * 하나뿐 — 항상 거부). 검증 없이 결제를 승인하면 존재하지 않는 주문에 대해 {@code payments}
     * 행이 쌓이는 데이터 무결성 문제가 생기므로(CodeRabbit 리뷰), 조용히 통과시키지 않고 여기서
     * 명시적으로 막는다. 승인 후 주문 상태 전이(구 {@code markPaid})는 2-B(Kafka Saga,
     * 2.6~2.14)에서 payment.completed 이벤트로 재구현한다 — order-service가 그 이벤트를
     * 구독해 자신의 상태를 갱신하는 방식이 유력하다.
     */
    @Idempotent(key = "#idempotencyKey")
    public PaymentResponse requestPayment(String idempotencyKey, RequestPaymentRequest request) {
        orderValidator.assertValid(request.orderId(), request.amount(), request.currency());

        Long paymentId = savePending(idempotencyKey, request);

        MockPgResult result = mockPgClient.requestPayment(idempotencyKey, request.amount(), request.currency());

        return applyResult(paymentId, result);
    }

    private static boolean isActiveOrderConflict(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains("ux_payments_active_order");
    }

    private Long savePending(String idempotencyKey, RequestPaymentRequest request) {
        try {
            return transactionTemplate.execute(status -> {
                Payment payment = new Payment(request.orderId(), idempotencyKey, request.amount(), request.currency());
                // saveAndFlush로 즉시 INSERT해야 ux_payments_active_order 위반이 여기서 바로
                // 드러난다 — save()만 쓰면 트랜잭션 커밋 시점까지 미뤄져 예외가 이 try 밖에서 난다.
                paymentRepository.saveAndFlush(payment);
                return payment.getId();
            });
        } catch (DataIntegrityViolationException e) {
            // validateOrder()의 읽기 시점 검사(order.payable())는 TOCTOU에 취약하다 — 서로 다른
            // 멱등키를 쓴 두 요청이 동시에 통과할 수 있다. ux_payments_active_order 유니크
            // 인덱스가 진짜 방어선이고, 여기서 그 위반을 도메인 예외로 번역한다.
            //
            // payments 테이블에는 idempotency_key 유니크 제약도 있어 같은 예외 타입으로 보고될
            // 수 있다 — 제약 이름을 확인해서 ux_payments_active_order 위반일 때만 주문 충돌로
            // 번역하고, 그 외(예: idempotency_key 중복)는 원래 예외를 그대로 던진다.
            if (isActiveOrderConflict(e)) {
                throw new PaymentOrderMismatchException(
                        "이미 처리 중이거나 완료된 결제가 있는 주문입니다: orderId=" + request.orderId(), e);
            }
            throw e;
        }
    }

    private PaymentResponse applyResult(Long paymentId, MockPgResult result) {
        return transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
            switch (result.outcome()) {
                // TODO(2-B): 승인 시 order-service에 결제 완료를 알리는 이벤트(payment.completed)를
                // 발행해야 한다. 이전에는 orderPort.markPaid()로 같은 트랜잭션에서 직접 처리했다.
                case APPROVED -> payment.approve(result.transactionId());
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
