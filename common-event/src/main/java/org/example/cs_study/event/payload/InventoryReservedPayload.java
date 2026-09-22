package org.example.cs_study.event.payload;

/**
 * {@code inventory.reserved} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 */
public record InventoryReservedPayload(
        Long orderId,
        Long productId,
        Integer quantity) {
}
