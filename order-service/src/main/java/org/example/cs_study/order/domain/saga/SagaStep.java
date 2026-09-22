package org.example.cs_study.order.domain.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.cs_study.common.exception.InvalidStateTransitionException;

/**
 * {@link SagaInstance}의 개별 스텝 시도 기록(로드맵 부록 A-2). {@code sagaId}는 소프트
 * 참조가 아니라 실제 FK다 — 같은 서비스(order-service) 소유 테이블이라 DB가 참조 무결성을
 * 보장해줄 수 있고, 굳이 마다할 이유가 없다(다른 서비스 DB를 참조하는 outbox 계열과 다름).
 *
 * <p>{@code payload} 컬럼은 처음부터 {@code TEXT}로 잡는다 — 기본 길이(255)로 뒀다가
 * PR #59(2.8, common-outbox)에서 CodeRabbit이 잡아준 문제를 여기서 반복하지 않기 위해서다.
 */
@Entity
@Table(name = "saga_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false, length = 36)
    private String sagaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_name", nullable = false, length = 20)
    private SagaStepName stepName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SagaStepStatus status;

    @Column(name = "request_payload", columnDefinition = "TEXT")
    private String requestPayload;

    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /** 낙관적 락(2.14) — {@link SagaInstance#getVersion()} Javadoc과 같은 이유. */
    @Version
    private Long version;

    public SagaStep(String sagaId, SagaStepName stepName, String requestPayload) {
        this.sagaId = sagaId;
        this.stepName = stepName;
        this.status = SagaStepStatus.PENDING;
        this.requestPayload = requestPayload;
    }

    public void succeed(String responsePayload) {
        transitionTo(SagaStepStatus.SUCCESS);
        this.responsePayload = responsePayload;
    }

    public void fail(String responsePayload) {
        transitionTo(SagaStepStatus.FAILED);
        this.responsePayload = responsePayload;
    }

    public void compensate() {
        transitionTo(SagaStepStatus.COMPENSATED);
    }

    private void transitionTo(SagaStepStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "Saga 스텝 상태를 %s에서 %s로 전이할 수 없습니다 (sagaId=%s, step=%s)"
                            .formatted(status, target, sagaId, stepName));
        }
        this.status = target;
    }

    @PrePersist
    void onCreate() {
        this.attemptedAt = Instant.now();
    }
}
