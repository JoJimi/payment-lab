package org.example.cs_study.order.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Saga 스텝 상태 전이표 검증. Docker 불필요. */
class SagaStepStatusTest {

    @Test
    void PENDING에서는_SUCCESS_FAILED로만_전이할_수_있다() {
        assertThat(SagaStepStatus.PENDING.canTransitionTo(SagaStepStatus.SUCCESS)).isTrue();
        assertThat(SagaStepStatus.PENDING.canTransitionTo(SagaStepStatus.FAILED)).isTrue();
        assertThat(SagaStepStatus.PENDING.canTransitionTo(SagaStepStatus.PENDING)).isFalse();
        assertThat(SagaStepStatus.PENDING.canTransitionTo(SagaStepStatus.COMPENSATED)).isFalse();
    }

    @Test
    void SUCCESS에서는_COMPENSATED로만_전이할_수_있다() {
        assertThat(SagaStepStatus.SUCCESS.canTransitionTo(SagaStepStatus.COMPENSATED)).isTrue();
        assertThat(SagaStepStatus.SUCCESS.canTransitionTo(SagaStepStatus.FAILED)).isFalse();
        assertThat(SagaStepStatus.SUCCESS.canTransitionTo(SagaStepStatus.PENDING)).isFalse();
    }

    @Test
    void FAILED와_COMPENSATED는_종료_상태라_어디로도_전이할_수_없다() {
        for (SagaStepStatus target : SagaStepStatus.values()) {
            assertThat(SagaStepStatus.FAILED.canTransitionTo(target)).isFalse();
            assertThat(SagaStepStatus.COMPENSATED.canTransitionTo(target)).isFalse();
        }
    }
}
