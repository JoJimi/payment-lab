package org.example.cs_study.order.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.OrderStatus;

public record OrderResponse(
        Long id, Long productId, Integer quantity, OrderStatus status, BigDecimal totalAmount, String currency, Instant createdAt) {

    public static OrderResponse from(Order order) {
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
