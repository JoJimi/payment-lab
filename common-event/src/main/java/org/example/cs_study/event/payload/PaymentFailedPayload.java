package org.example.cs_study.event.payload;

import java.math.BigDecimal;

/**
 * {@code payment.failed} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 */
public record PaymentFailedPayload(
        Long orderId,
        Long paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
