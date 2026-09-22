package org.example.cs_study.order.repository;

import java.util.List;
import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSagaStepRepository extends JpaRepository<SagaStep, Long> {

    List<SagaStep> findBySagaIdOrderByIdAsc(String sagaId);

    Optional<SagaStep> findBySagaIdAndStepName(String sagaId, SagaStepName stepName);
}
