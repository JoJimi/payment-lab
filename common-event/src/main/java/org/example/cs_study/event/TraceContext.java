package org.example.cs_study.event;

import org.slf4j.MDC;

/**
 * 컨슈머 스레드에서 {@link EventEnvelope#traceId()}를 MDC로 복원한다. MDC는 ThreadLocal이라
 * Kafka 컨슈머 스레드는 발행 스레드의 MDC를 전혀 모른다 — 봉투에 실어온 traceId를 여기서
 * 다시 채워야 컨슈머 쪽 로그도 같은 traceId로 묶여 분산 추적(4.4)이 된다.
 *
 * <p>try-with-resources로 쓴다: 이벤트 처리 블록이 끝나면 이전 MDC 상태로 되돌려, 같은
 * 컨슈머 스레드가 다음 이벤트를 처리할 때 이전 traceId가 새어 들어가지 않게 한다.
 *
 * <pre>{@code
 * try (var ignored = TraceContext.restore(envelope.traceId())) {
 *     // 이 블록 안의 로그는 발행 시점의 traceId를 그대로 갖는다
 * }
 * }</pre>
 */
public final class TraceContext {

    private static final String MDC_TRACE_ID_KEY = "traceId";

    private TraceContext() {
    }

    public static AutoCloseable restore(String traceId) {
        String previous = MDC.get(MDC_TRACE_ID_KEY);
        if (traceId != null) {
            MDC.put(MDC_TRACE_ID_KEY, traceId);
        } else {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
        return () -> {
            if (previous != null) {
                MDC.put(MDC_TRACE_ID_KEY, previous);
            } else {
                MDC.remove(MDC_TRACE_ID_KEY);
            }
        };
    }
}
