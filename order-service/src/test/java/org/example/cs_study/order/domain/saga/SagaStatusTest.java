package org.example.cs_study.order.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/domain/state-transitions.md의 Saga 상태 전이표를 코드로 옮겼는지 검증. Docker 불필요. */
class SagaStatusTest {

    @Test
    void STARTED에서는_COMPLETED_COMPENSATING으로만_전이할_수_있다() {
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.COMPLETED)).isTrue();
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.STARTED)).isFalse();
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.FAILED)).isFalse();
    }

    @Test
    void COMPENSATING에서는_COMPLETED_FAILED로만_전이할_수_있다() {
        assertThat(SagaStatus.COMPENSATING.canTransitionTo(SagaStatus.COMPLETED)).isTrue();
        assertThat(SagaStatus.COMPENSATING.canTransitionTo(SagaStatus.FAILED)).isTrue();
        assertThat(SagaStatus.COMPENSATING.canTransitionTo(SagaStatus.STARTED)).isFalse();
        assertThat(SagaStatus.COMPENSATING.canTransitionTo(SagaStatus.COMPENSATING)).isFalse();
    }

    @Test
    void COMPLETED와_FAILED는_종료_상태라_어디로도_전이할_수_없다() {
        for (SagaStatus target : SagaStatus.values()) {
            assertThat(SagaStatus.COMPLETED.canTransitionTo(target)).isFalse();
            assertThat(SagaStatus.FAILED.canTransitionTo(target)).isFalse();
        }
    }
}
