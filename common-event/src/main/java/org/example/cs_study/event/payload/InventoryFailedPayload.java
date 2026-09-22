package org.example.cs_study.event.payload;

/**
 * {@code inventory.failed} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 */
public record InventoryFailedPayload(
        Long orderId,
        Long productId,
        Integer quantity,
        String reason) {
}
