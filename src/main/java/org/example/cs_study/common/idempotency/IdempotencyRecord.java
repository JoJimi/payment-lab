package org.example.cs_study.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 멱등성 2차 방어(DB unique 제약, 부록 A-1). Redis가 놓친 경합(Redis 장애 등)을 이 테이블의
 * unique 제약이 막는다. 도메인 중립 — 결제 외 다른 {@code @Idempotent} 메서드에도 재사용된다.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** 원본 응답 JSON. 재현 시 그대로 역직렬화해서 반환한다 (409가 아니라 진짜 응답). */
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public IdempotencyRecord(String idempotencyKey, Duration ttl) {
        this.idempotencyKey = idempotencyKey;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(ttl);
    }

    public void complete(int responseStatus, String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }
}
