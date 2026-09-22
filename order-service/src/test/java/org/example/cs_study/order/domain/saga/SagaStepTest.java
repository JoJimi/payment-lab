package org.example.cs_study.order.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.example.cs_study.common.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

/** {@link SagaStep} 엔티티가 {@link SagaStepStatus} 전이표를 실제로 강제하는지 검증. Docker 불필요. */
class SagaStepTest {

    @Test
    void 생성_직후_상태는_PENDING이다() {
        SagaStep step = new SagaStep("saga-1", SagaStepName.PAYMENT, "{}");

        assertThat(step.getStatus()).isEqualTo(SagaStepStatus.PENDING);
        assertThat(step.getResponsePayload()).isNull();
    }

    @Test
    void succeed하면_SUCCESS로_바뀌고_응답_페이로드가_남는다() {
        SagaStep step = new SagaStep("saga-1", SagaStepName.PAYMENT, "{}");

        step.succeed("{\"paymentId\":1}");

        assertThat(step.getStatus()).isEqualTo(SagaStepStatus.SUCCESS);
        assertThat(step.getResponsePayload()).isEqualTo("{\"paymentId\":1}");
    }

    @Test
    void fail하면_FAILED로_바뀌고_종료_상태가_된다() {
        SagaStep step = new SagaStep("saga-1", SagaStepName.INVENTORY, "{}");

        step.fail("재고 부족");

        assertThat(step.getStatus()).isEqualTo(SagaStepStatus.FAILED);
        assertThatThrownBy(step::compensate).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void SUCCESS한_스텝만_compensate할_수_있다() {
        SagaStep step = new SagaStep("saga-1", SagaStepName.INVENTORY, "{}");
        step.succeed("{}");

        step.compensate();

        assertThat(step.getStatus()).isEqualTo(SagaStepStatus.COMPENSATED);
    }

    @Test
    void PENDING_상태에서_바로_compensate하면_예외가_난다() {
        SagaStep step = new SagaStep("saga-1", SagaStepName.PAYMENT, "{}");

        assertThatThrownBy(step::compensate).isInstanceOf(InvalidStateTransitionException.class);
    }
}
