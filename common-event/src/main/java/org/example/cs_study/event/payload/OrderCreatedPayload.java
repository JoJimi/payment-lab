package org.example.cs_study.event.payload;

import java.math.BigDecimal;

/**
 * {@code order.created} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 */
public record OrderCreatedPayload(
        Long orderId,
        Long productId,
        Integer quantity,
        BigDecimal totalAmount,
        String currency) {
}
