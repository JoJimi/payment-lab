package org.example.cs_study.order.domain.saga;

/**
 * Saga 개별 스텝 상태 전이 규칙. {@code FAILED}는 종착 상태다 — 한 번도 성공한 적 없는
 * 스텝은 보상할 대상이 없다(보상은 이미 벌어진 부수효과를 되돌리는 것이므로).
 * {@code SUCCESS}만 {@code COMPENSATED}로 갈 수 있다.
 */
public enum SagaStepStatus {
    PENDING,
    SUCCESS,
    FAILED,
    COMPENSATED;

    public boolean canTransitionTo(SagaStepStatus target) {
        return switch (this) {
            case PENDING -> target == SUCCESS || target == FAILED;
            case SUCCESS -> target == COMPENSATED;
            case FAILED, COMPENSATED -> false;
        };
    }
}
