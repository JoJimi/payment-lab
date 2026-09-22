package org.example.cs_study.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void restore_블록_안에서는_MDC에_traceId가_채워진다() throws Exception {
        try (var ignored = TraceContext.restore("trace-abc")) {
            assertThat(MDC.get("traceId")).isEqualTo("trace-abc");
        }
    }

    @Test
    void close되면_이전_MDC_상태로_되돌아간다() throws Exception {
        MDC.put("traceId", "original");

        try (var ignored = TraceContext.restore("trace-abc")) {
            assertThat(MDC.get("traceId")).isEqualTo("trace-abc");
        }

        assertThat(MDC.get("traceId")).isEqualTo("original");
    }

    @Test
    void 이전_traceId가_없었으면_close_후_MDC가_비워진다() throws Exception {
        try (var ignored = TraceContext.restore("trace-abc")) {
            assertThat(MDC.get("traceId")).isEqualTo("trace-abc");
        }

        assertThat(MDC.get("traceId")).isNull();
    }
}
