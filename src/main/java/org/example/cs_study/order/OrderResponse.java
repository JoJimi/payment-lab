package org.example.cs_study.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        Long id, Long productId, Integer quantity, OrderStatus status, BigDecimal totalAmount, String currency, Instant createdAt) {

    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getProductId(),
                order.getQuantity(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getCreatedAt());
    }
}
