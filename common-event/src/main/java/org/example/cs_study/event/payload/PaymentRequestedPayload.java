package org.example.cs_study.event.payload;

import java.math.BigDecimal;

/**
 * {@code payment.requested} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 *
 * <p>{@code idempotencyKey}는 1단계 멱등성 인프라(로드맵 부록 A-1)를 재사용한다 — Saga
 * 재시도로 같은 커맨드가 두 번 발행돼도 payment-service가 중복 결제를 만들지 않게 한다.
 */
public record PaymentRequestedPayload(
        Long orderId,
        BigDecimal amount,
        String currency,
        String idempotencyKey) {
}
