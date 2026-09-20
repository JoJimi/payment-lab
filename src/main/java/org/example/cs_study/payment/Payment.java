package org.example.cs_study.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.cs_study.common.InvalidStateTransitionException;

/**
 * {@code orderId}는 {@code order} 패키지 엔티티를 참조하지 않는 소프트 참조(순수 ID)다.
 * {@code idempotencyKey}에 DB unique 제약을 걸어 멱등성 2차 방어(부록 A-1)의 일부로 쓴다.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "pg_transaction_id")
    private String pgTransactionId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Payment(Long orderId, String idempotencyKey, BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
    }

    public void approve(String pgTransactionId) {
        transitionTo(PaymentStatus.APPROVED);
        this.pgTransactionId = pgTransactionId;
        this.approvedAt = Instant.now();
    }

    public void fail() {
        transitionTo(PaymentStatus.FAILED);
    }

    /** PG 응답 타임아웃. FAILED로 단정하지 않는다 (CLAUDE.md 코드 규칙). */
    public void markUnknown() {
        transitionTo(PaymentStatus.UNKNOWN);
    }

    /** UNKNOWN 상태를 재조회 결과로 확정한다 (3.4에서 실제 조회 로직 연결 예정). */
    public void resolveFromUnknown(boolean approved, String pgTransactionId) {
        if (approved) {
            approve(pgTransactionId);
        } else {
            fail();
        }
    }

    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED);
    }

    private void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "결제 상태를 %s에서 %s로 전이할 수 없습니다 (paymentId=%s)".formatted(status, target, id));
        }
        this.status = target;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.requestedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
