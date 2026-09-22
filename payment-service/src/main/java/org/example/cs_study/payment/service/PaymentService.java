package org.example.cs_study.payment.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.cs_study.common.exception.payment.PaymentNotFoundException;
import org.example.cs_study.common.exception.payment.PaymentOrderMismatchException;
import org.example.cs_study.common.idempotency.Idempotent;
import org.example.cs_study.common.outbox.OutboxService;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.PaymentCompletedPayload;
import org.example.cs_study.event.payload.PaymentFailedPayload;
import org.example.cs_study.payment.client.MockPgClient;
import org.example.cs_study.payment.client.MockPgResult;
import org.example.cs_study.payment.domain.Payment;
import org.example.cs_study.payment.domain.PaymentStatus;
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
    private final OutboxService outboxService;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderValidator orderValidator,
            MockPgClient mockPgClient,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.orderValidator = orderValidator;
        this.mockPgClient = mockPgClient;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxService = outboxService;
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
     * 명시적으로 막는다. REST로 직접 호출하는 경로는 익명 호출자가 임의 주문 번호를 댈 수 있어
     * 계속 막아둔다 — Saga가 실제로 결제를 트리거하는 경로는 {@link #requestPaymentFromSaga}
     * 다(2.12).
     */
    @Idempotent(key = "#idempotencyKey")
    public PaymentResponse requestPayment(String idempotencyKey, RequestPaymentRequest request) {
        orderValidator.assertValid(request.orderId(), request.amount(), request.currency());
        return doRequestPayment(idempotencyKey, request);
    }

    /**
     * Kafka {@code payment.requested} 리스너 전용 진입점(2.12, {@code PaymentRequestedListener}).
     * {@link #requestPayment}와 달리 {@link OrderValidator}를 거치지 않는다 — 이 커맨드는
     * order-service가 주문을 실제로 생성한 직후, 같은 트랜잭션에서 Outbox로 발행한 것이다.
     * 메시지가 존재한다는 사실 자체가 "주문이 실재한다"는 증거이고(발행자는 이 시스템에서
     * 유일하게 신뢰할 수 있는 주문 생성 권한자), 별도로 주문을 재검증할 이유가 없다 — 오히려
     * order-service와 payment-service 사이에 존재하지도 않는 동기 조회를 또 만드는 셈이 된다.
     */
    @Idempotent(key = "#idempotencyKey")
    public PaymentResponse requestPaymentFromSaga(String idempotencyKey, RequestPaymentRequest request) {
        return doRequestPayment(idempotencyKey, request);
    }

    private PaymentResponse doRequestPayment(String idempotencyKey, RequestPaymentRequest request) {
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

    /**
     * 승인/거절 결과를 같은 트랜잭션에서 {@code payments} 갱신 + Outbox 적재까지 묶는다(2.12).
     * {@code TIMEOUT}(UNKNOWN)은 어느 쪽도 아니다 — PG가 실제로 승인했는지 알 수 없는 상태라
     * {@code payment.completed}도 {@code payment.failed}도 쏘지 않는다. 이 경우 order-service의
     * Saga는 PAYMENT 단계에 그대로 멈춰 있다가 2.15의 타임아웃 스케줄러가 회수한다 — 여기서
     * 섣불리 완료/실패로 단정하면 3.4에서 실제 조회로 해소하려는 UNKNOWN 상태의 의미가 없어진다.
     */
    private PaymentResponse applyResult(Long paymentId, MockPgResult result) {
        return transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
            switch (result.outcome()) {
                case APPROVED -> {
                    payment.approve(result.transactionId());
                    outboxService.save(
                            EventType.PAYMENT_COMPLETED,
                            "Payment",
                            payment.getId().toString(),
                            new PaymentCompletedPayload(
                                    payment.getOrderId(),
                                    payment.getId(),
                                    payment.getPgTransactionId(),
                                    payment.getAmount(),
                                    payment.getCurrency(),
                                    payment.getApprovedAt()));
                }
                case FAILED -> {
                    payment.fail();
                    outboxService.save(
                            EventType.PAYMENT_FAILED,
                            "Payment",
                            payment.getId().toString(),
                            new PaymentFailedPayload(
                                    payment.getOrderId(), payment.getId(), payment.getAmount(), payment.getCurrency(), result.errorCode()));
                }
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

    /**
     * Saga 보상 트랜잭션의 실행 지점(2.13, {@code OrderCancelledListener}) — 이 주문에 결제가
     * 승인(APPROVED)된 상태로 남아있으면 취소한다. APPROVED가 아니면(결제 자체가 실패해서
     * 보상이 시작됐거나, 이미 취소된 재전달이거나) 조용히 넘어간다 — {@link Payment#cancel}은
     * APPROVED에서만 허용되는 전이라 호출 전에 걸러야 한다. 이 필터링 자체가 이 메서드를
     * 멱등하게 만든다(2.14 "보상 자체의 멱등성"을 여기서 미리 만족).
     */
    @Transactional
    public void cancelForOrder(Long orderId) {
        paymentRepository.findByOrderId(orderId).stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.APPROVED)
                .forEach(payment -> {
                    payment.cancel();
                    paymentRepository.save(payment);
                });
    }
}
