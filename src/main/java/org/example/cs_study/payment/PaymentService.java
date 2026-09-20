package org.example.cs_study.payment;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.cs_study.common.idempotency.Idempotent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MockPgClient mockPgClient;
    private final MeterRegistry meterRegistry;

    public PaymentService(PaymentRepository paymentRepository, MockPgClient mockPgClient, MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.mockPgClient = mockPgClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 멱등 키 검증이 비즈니스 로직보다 먼저 수행된다 — {@link Idempotent} AOP가 이 메서드
     * 진입 자체를 가로챈다(부록 A-1의 2단 방어). 동일 키 재요청은 이 바디를 다시 실행하지
     * 않고 저장된 원본 응답을 그대로 재현한다 (1.8, 1.9).
     */
    @Idempotent(key = "#idempotencyKey")
    @Transactional
    public PaymentResponse requestPayment(String idempotencyKey, RequestPaymentRequest request) {
        Payment payment = new Payment(request.orderId(), idempotencyKey, request.amount(), request.currency());
        paymentRepository.save(payment);

        MockPgResult result = mockPgClient.requestPayment(idempotencyKey, request.amount(), request.currency());
        switch (result.outcome()) {
            case APPROVED -> payment.approve(result.transactionId());
            case FAILED -> payment.fail();
            case TIMEOUT -> payment.markUnknown();
        }
        // 1.19: 결제 성공/실패(+UNKNOWN) 카운터. status 태그로 나눠 Grafana에서 비율을 본다.
        meterRegistry.counter("payment.result", "status", payment.getStatus().name()).increment();
        return PaymentResponse.from(payment);
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
