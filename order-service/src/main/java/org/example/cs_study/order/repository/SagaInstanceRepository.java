package org.example.cs_study.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaInstance;

/** Saga 인스턴스 저장소 포트. 실제 구현은 {@link org.example.cs_study.order.repository.adapter.SagaInstanceRepositoryAdapter}. */
public interface SagaInstanceRepository {

    SagaInstance save(SagaInstance sagaInstance);

    Optional<SagaInstance> findById(String sagaId);

    Optional<SagaInstance> findByOrderId(Long orderId);

    /**
     * {@code SagaTimeoutScheduler}(2.15)가 회수 대상을 찾을 때 쓴다 — {@code STARTED} 상태로
     * {@code timeoutAt}을 넘긴 Saga(idx_saga_instance_status_timeout, 2.11에서 이미 이 용도로
     * 만들어둔 인덱스).
     */
    List<SagaInstance> findTimedOutStartedSagas(Instant now);
}
