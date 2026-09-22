package org.example.cs_study.event;

/**
 * {@link EventEnvelope#eventType()}에 들어가는 값이자 Kafka 토픽명이다(1:1 대응,
 * docs/architecture/event-catalog.md 참고). 문자열 리터럴을 서비스마다 따로 들고 있으면
 * 오타로 토픽이 갈라지는 사고가 나기 쉬워, 계약을 이 enum 하나로 고정한다.
 */
public enum EventType {

    ORDER_CREATED("order.created"),
    PAYMENT_REQUESTED("payment.requested"),
    PAYMENT_COMPLETED("payment.completed"),
    PAYMENT_FAILED("payment.failed"),
    INVENTORY_RESERVED("inventory.reserved"),
    INVENTORY_FAILED("inventory.failed"),
    ORDER_CANCELLED("order.cancelled"),
    NOTIFICATION_REQUESTED("notification.requested");

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }
}
