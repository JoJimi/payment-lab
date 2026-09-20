package org.example.cs_study.payment;

/**
 * 결제 상태 전이 규칙. 표는 docs/domain/state-transitions.md 참고.
 * UNKNOWN은 PG 응답 타임아웃 전용 상태 — FAILED로 단정하지 않는다 (CLAUDE.md 코드 규칙).
 */
public enum PaymentStatus {
    PENDING,
    APPROVED,
    FAILED,
    UNKNOWN,
    CANCELLED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case PENDING -> target == APPROVED || target == FAILED || target == UNKNOWN;
            case UNKNOWN -> target == APPROVED || target == FAILED;
            case APPROVED -> target == CANCELLED;
            case FAILED, CANCELLED -> false;
        };
    }
}
