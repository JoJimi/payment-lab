package org.example.cs_study.event.payload;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code payment.completed} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 */
public record PaymentCompletedPayload(
        Long orderId,
        Long paymentId,
        String pgTransactionId,
        BigDecimal amount,
        String currency,
        Instant approvedAt) {
}
