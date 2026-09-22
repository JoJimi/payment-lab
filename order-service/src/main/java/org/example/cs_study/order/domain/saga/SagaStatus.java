package org.example.cs_study.order.domain.saga;

/**
 * Saga 인스턴스 상태 전이 규칙. 표는 docs/domain/state-transitions.md 참고.
 *
 * <p>{@code COMPENSATING}에서 갈라지는 두 종착점이 핵심이다 — 보상이 끝까지 성공하면
 * {@code COMPLETED}(주문이 정상적으로 취소 처리된 것도 "완료"다), 보상 자체가 실패하면
 * {@code FAILED}(사람 개입 또는 DLQ 재처리 대상, 2.16)로 갈라진다.
 */
public enum SagaStatus {
    STARTED,
    COMPENSATING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(SagaStatus target) {
        return switch (this) {
            case STARTED -> target == COMPLETED || target == COMPENSATING;
            case COMPENSATING -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
