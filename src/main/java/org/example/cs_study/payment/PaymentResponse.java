package org.example.cs_study.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        Long orderId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String pgTransactionId,
        Instant requestedAt,
        Instant approvedAt) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPgTransactionId(),
                payment.getRequestedAt(),
                payment.getApprovedAt());
    }
}
