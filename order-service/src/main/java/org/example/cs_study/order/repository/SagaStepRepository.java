package org.example.cs_study.order.repository;

import java.util.List;
import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;

/** Saga 스텝 저장소 포트. 실제 구현은 {@link org.example.cs_study.order.repository.adapter.SagaStepRepositoryAdapter}. */
public interface SagaStepRepository {

    SagaStep save(SagaStep sagaStep);

    List<SagaStep> findBySagaId(String sagaId);

    /** 리스너가 "이 sagaId의 PAYMENT 스텝"처럼 특정 단계를 콕 집어 전이시킬 때 쓴다(2.12). */
    Optional<SagaStep> findBySagaIdAndStepName(String sagaId, SagaStepName stepName);
}
