package org.example.cs_study.event;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link EventEnvelopeFactory}(발행 시점 봉투 생성)의 대칭 짝 — 컨슈머가 Kafka에서 받은
 * JSON 문자열을 다시 {@code EventEnvelope<T>}로 되돌린다. 제네릭 페이로드 타입은 컴파일
 * 타임에 지워지므로({@code EventEnvelope<PaymentCompletedPayload>}) 단순
 * {@code readValue(json, EventEnvelope.class)}로는 payload 필드가 {@code LinkedHashMap}으로
 * 남는다 — {@link ObjectMapper#getTypeFactory()}로 매개변수화 타입을 직접 구성해야 한다.
 */
public final class EventEnvelopeReader {

    private EventEnvelopeReader() {
    }

    public static <T> EventEnvelope<T> read(ObjectMapper objectMapper, String json, Class<T> payloadType) {
        JavaType envelopeType = objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        return objectMapper.readValue(json, envelopeType);
    }
}
