package org.example.cs_study.order.domain.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.cs_study.common.exception.InvalidStateTransitionException;

/**
 * Saga 진행 상태를 추적하는 애그리거트 루트(로드맵 부록 A-2). Order Service가 조율자이므로
 * 이 서비스가 소유한다. 개별 스텝 기록은 {@link SagaStep}(별도 테이블, 1:N).
 *
 * <p>{@code sagaId}를 {@code orderId}와 별도의 UUID로 두는 이유: 지금은 주문 1건당 Saga
 * 1개뿐이라 {@code orderId}를 PK로 써도 되지만, 그러면 나중에 재시도 Saga가 필요해질 때
 * 식별자 스키마 자체를 바꿔야 한다. 대신 지금은 {@code order_id}에 유니크 제약(DB 레벨)을
 * 걸어 "주문 1건당 Saga 1개"라는 현재 불변식을 강제한다.
 */
@Entity
@Table(name = "saga_instance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaInstance {

    @Id
    @Column(name = "saga_id", length = 36)
    private String sagaId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SagaStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 20)
    private SagaStepName currentStep;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "timeout_at", nullable = false)
    private Instant timeoutAt;

    /**
     * 낙관적 락(2.14) — 2.13에서 여러 Kafka 리스너(정상/보상 양쪽)가 같은 sagaId 행을
     * 건드리게 됐다. 같은 이벤트가 서로 다른 eventId로 중복 발행되면(예: inventory-service가
     * 버그로 {@code inventory.failed}를 두 번 쏘는 경우) {@code InboxService}의 eventId
     * 기반 중복 방지를 우회한다 — 두 트랜잭션이 이 행을 동시에 읽어 둘 다 STARTED로 보고
     * 진행하면 그중 하나가 여기서 걸린다. inventory-service의 {@code Inventory.version}(1.11)과
     * 같은 패턴.
     */
    @Version
    private Long version;

    /**
     * @param timeoutAt 이 시각을 넘겨도 STARTED에 머무르면 2.15 스케줄러가 회수 대상으로 본다.
     *     타임아웃 정책(얼마나 기다릴지)은 호출자(오케스트레이션 서비스, 2.12)가 결정한다 —
     *     엔티티는 시각 계산을 하지 않는다.
     */
    public SagaInstance(Long orderId, Instant timeoutAt) {
        this.sagaId = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.status = SagaStatus.STARTED;
        this.currentStep = null;
        this.timeoutAt = timeoutAt;
    }

    public void advanceTo(SagaStepName step) {
        this.currentStep = step;
    }

    public void complete() {
        transitionTo(SagaStatus.COMPLETED);
    }

    public void beginCompensation() {
        transitionTo(SagaStatus.COMPENSATING);
    }

    public void failCompensation() {
        transitionTo(SagaStatus.FAILED);
    }

    private void transitionTo(SagaStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "Saga 상태를 %s에서 %s로 전이할 수 없습니다 (sagaId=%s)".formatted(status, target, sagaId));
        }
        this.status = target;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
