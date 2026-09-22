package org.example.cs_study.common.outbox;

/** {@code outbox.status} 컬럼 값(2.2에서 정의된 스키마와 1:1 대응). */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
