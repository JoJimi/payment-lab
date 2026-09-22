package org.example.cs_study.order.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.example.cs_study.common.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

/** {@link SagaInstance} 엔티티가 {@link SagaStatus} 전이표를 실제로 강제하는지 검증. Docker 불필요. */
class SagaInstanceTest {

    private static SagaInstance newInstance() {
        return new SagaInstance(1L, Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    @Test
    void 생성_직후_상태는_STARTED이고_currentStep은_없다() {
        SagaInstance saga = newInstance();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.STARTED);
        assertThat(saga.getCurrentStep()).isNull();
        assertThat(saga.getOrderId()).isEqualTo(1L);
        assertThat(saga.getSagaId()).isNotBlank();
    }

    @Test
    void advanceTo는_currentStep만_바꾸고_status는_그대로다() {
        SagaInstance saga = newInstance();

        saga.advanceTo(SagaStepName.PAYMENT);

        assertThat(saga.getCurrentStep()).isEqualTo(SagaStepName.PAYMENT);
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.STARTED);
    }

    @Test
    void STARTED에서_바로_complete할_수_있다() {
        SagaInstance saga = newInstance();

        saga.complete();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    void 보상을_거쳐도_complete로_끝날_수_있다() {
        SagaInstance saga = newInstance();

        saga.beginCompensation();
        saga.complete();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    void 보상_자체가_실패하면_FAILED로_끝난다() {
        SagaInstance saga = newInstance();

        saga.beginCompensation();
        saga.failCompensation();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.FAILED);
    }

    @Test
    void 완료된_Saga를_다시_전이시키려_하면_예외가_난다() {
        SagaInstance saga = newInstance();
        saga.complete();

        assertThatThrownBy(saga::beginCompensation).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void STARTED에서_바로_failCompensation을_호출하면_예외가_난다() {
        SagaInstance saga = newInstance();

        assertThatThrownBy(saga::failCompensation).isInstanceOf(InvalidStateTransitionException.class);
    }
}
