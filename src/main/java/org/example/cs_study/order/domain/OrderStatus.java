package org.example.cs_study.order.domain;

/**
 * 주문 상태 전이 규칙. 표는 docs/domain/state-transitions.md 참고.
 * 종료 상태(FAILED/CANCELLED)에서는 어떤 전이도 허용하지 않는다 — 재시도는 새 주문으로.
 */
public enum OrderStatus {
    CREATED,
    PAID,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PAID || target == FAILED || target == CANCELLED;
            case PAID -> target == CANCELLED;
            case FAILED, CANCELLED -> false;
        };
    }
}
