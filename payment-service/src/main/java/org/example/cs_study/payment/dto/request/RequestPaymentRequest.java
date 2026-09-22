package org.example.cs_study.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RequestPaymentRequest(@NotNull Long orderId, @NotNull BigDecimal amount, @NotBlank String currency) {
}
