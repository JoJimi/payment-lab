package org.example.cs_study.order.repository;

import java.util.List;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSagaStepRepository extends JpaRepository<SagaStep, Long> {

    List<SagaStep> findBySagaIdOrderByIdAsc(String sagaId);
}
