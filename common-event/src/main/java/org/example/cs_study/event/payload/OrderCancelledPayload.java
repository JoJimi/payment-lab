package org.example.cs_study.event.payload;

/**
 * {@code order.cancelled} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 * 보상 트랜잭션(로드맵 부록 A-4, 2.13) 개시를 알린다.
 */
public record OrderCancelledPayload(
        Long orderId,
        String reason) {
}
