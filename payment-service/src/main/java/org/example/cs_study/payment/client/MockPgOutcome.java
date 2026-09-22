package org.example.cs_study.payment.client;

/** TIMEOUT은 PG가 승인했는지 알 수 없는 상태 — FAILED로 단정하지 않는다 (CLAUDE.md 코드 규칙). */
public enum MockPgOutcome {
    APPROVED,
    FAILED,
    TIMEOUT
}
