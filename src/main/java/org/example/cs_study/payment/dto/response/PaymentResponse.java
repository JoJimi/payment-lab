package org.example.cs_study.payment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import org.example.cs_study.payment.domain.Payment;
import org.example.cs_study.payment.domain.PaymentStatus;

public record PaymentResponse(
        Long id,
        Long orderId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String pgTransactionId,
        Instant requestedAt,
        Instant approvedAt) {

    public static PaymentResponse from(Payment payment) {
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
