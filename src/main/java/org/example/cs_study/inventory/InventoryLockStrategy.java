package org.example.cs_study.inventory;

/** 로드맵 1.11 — 재고 차감을 4가지 방식으로 구현하고 스위치로 전환 가능하게. */
public enum InventoryLockStrategy {
    /** 락 없음. Lost Update를 의도적으로 재현하기 위한 대조군. */
    NONE,
    /** {@code SELECT ... FOR UPDATE}. */
    PESSIMISTIC,
    /** {@code @Version} + 충돌 시 재시도. */
    OPTIMISTIC,
    /** Redisson {@code RLock}. */
    DISTRIBUTED
}
