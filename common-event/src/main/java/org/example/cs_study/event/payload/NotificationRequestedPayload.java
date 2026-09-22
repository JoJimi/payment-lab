package org.example.cs_study.event.payload;

/**
 * {@code notification.requested} 토픽 페이로드. docs/architecture/event-catalog.md 참고.
 * Saga 종료(완료 또는 취소) 시 order-service가 발행한다.
 */
public record NotificationRequestedPayload(
        Long orderId,
        NotificationType type,
        String message) {
}
