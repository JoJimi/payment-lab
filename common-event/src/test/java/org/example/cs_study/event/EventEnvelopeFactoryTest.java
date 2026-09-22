package org.example.cs_study.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.cs_study.event.payload.OrderCancelledPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class EventEnvelopeFactoryTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 기본_버전으로_봉투를_만들면_eventId와_occurredAt이_채워진다() {
        OrderCancelledPayload payload = new OrderCancelledPayload(1L, "재고 부족");

        EventEnvelope<OrderCancelledPayload> envelope =
                EventEnvelopeFactory.create(EventType.ORDER_CANCELLED, payload);

        assertThat(envelope.eventId()).isNotBlank();
        assertThat(envelope.eventType()).isEqualTo("order.cancelled");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.occurredAt()).isNotNull();
        assertThat(envelope.payload()).isEqualTo(payload);
    }

    @Test
    void MDC에_traceId가_있으면_봉투에_실린다() {
        MDC.put("traceId", "trace-123");

        EventEnvelope<OrderCancelledPayload> envelope = EventEnvelopeFactory.create(
                EventType.ORDER_CANCELLED, new OrderCancelledPayload(1L, "타임아웃"));

        assertThat(envelope.traceId()).isEqualTo("trace-123");
    }

    @Test
    void MDC에_traceId가_없으면_null이다() {
        EventEnvelope<OrderCancelledPayload> envelope = EventEnvelopeFactory.create(
                EventType.ORDER_CANCELLED, new OrderCancelledPayload(1L, "타임아웃"));

        assertThat(envelope.traceId()).isNull();
    }

    @Test
    void 버전을_명시하면_그대로_반영된다() {
        EventEnvelope<OrderCancelledPayload> envelope = EventEnvelopeFactory.create(
                EventType.ORDER_CANCELLED, 2, new OrderCancelledPayload(1L, "스키마 변경 테스트"));

        assertThat(envelope.version()).isEqualTo(2);
    }
}
