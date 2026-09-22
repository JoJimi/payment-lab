package org.example.cs_study.order.repository;

import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaInstance;

/** Saga 인스턴스 저장소 포트. 실제 구현은 {@link org.example.cs_study.order.repository.adapter.SagaInstanceRepositoryAdapter}. */
public interface SagaInstanceRepository {

    SagaInstance save(SagaInstance sagaInstance);

    Optional<SagaInstance> findById(String sagaId);

    Optional<SagaInstance> findByOrderId(Long orderId);
}
