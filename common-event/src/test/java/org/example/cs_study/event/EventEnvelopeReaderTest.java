package org.example.cs_study.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.example.cs_study.event.payload.PaymentRequestedPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** {@link EventEnvelopeFactory}로 만든 봉투가 직렬화/역직렬화를 왕복해도 그대로 복원되는지 검증. */
class EventEnvelopeReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 발행_시점_봉투를_JSON으로_직렬화했다가_그대로_복원할_수_있다() {
        PaymentRequestedPayload payload = new PaymentRequestedPayload(1L, new BigDecimal("1000.0000"), "KRW", "idem-1");
        EventEnvelope<PaymentRequestedPayload> original = EventEnvelopeFactory.create(EventType.PAYMENT_REQUESTED, payload);

        String json = objectMapper.writeValueAsString(original);
        EventEnvelope<PaymentRequestedPayload> restored =
                EventEnvelopeReader.read(objectMapper, json, PaymentRequestedPayload.class);

        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.eventType()).isEqualTo(original.eventType());
        assertThat(restored.version()).isEqualTo(original.version());
        assertThat(restored.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(restored.payload()).isEqualTo(original.payload());
    }
}
