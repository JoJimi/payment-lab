package org.example.cs_study.event;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * {@link EventEnvelope}를 만드는 유일한 지점. {@code traceId}를 여기서 채우는 이유는
 * MDC가 ThreadLocal이라 Kafka 컨슈머 스레드에서는 끊기기 때문이다 — 발행 시점(현재 요청
 * 스레드의 MDC가 살아있는 시점)에 봉투에 실어 날라야, 컨슈머 쪽에서 {@link TraceContext}로
 * 복원해 분산 로그 추적(4.4)이 가능해진다.
 *
 * <p>traceId는 micrometer-tracing-bridge-brave가 요청 처리 중 MDC에 채워둔다. 배치/스케줄러
 * 등 요청 컨텍스트 밖에서 호출하면 MDC가 비어 있으므로 {@code traceId}는 {@code null}이 된다
 * — 이 자체는 오류가 아니다(추적 대상 요청이 없다는 뜻).
 */
public final class EventEnvelopeFactory {

    private static final String MDC_TRACE_ID_KEY = "traceId";
    private static final int DEFAULT_VERSION = 1;

    private EventEnvelopeFactory() {
    }

    public static <T> EventEnvelope<T> create(EventType eventType, T payload) {
        return create(eventType, DEFAULT_VERSION, payload);
    }

    public static <T> EventEnvelope<T> create(EventType eventType, int version, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType.topic(),
                version,
                Instant.now(),
                MDC.get(MDC_TRACE_ID_KEY),
                payload);
    }
}
