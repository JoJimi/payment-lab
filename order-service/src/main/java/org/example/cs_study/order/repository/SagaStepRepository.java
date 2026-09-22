package org.example.cs_study.order.repository;

import java.util.List;
import org.example.cs_study.order.domain.saga.SagaStep;

/** Saga 스텝 저장소 포트. 실제 구현은 {@link org.example.cs_study.order.repository.adapter.SagaStepRepositoryAdapter}. */
public interface SagaStepRepository {

    SagaStep save(SagaStep sagaStep);

    List<SagaStep> findBySagaId(String sagaId);
}
