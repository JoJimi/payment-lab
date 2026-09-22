package org.example.cs_study.order.repository.adapter;

import java.util.List;
import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.example.cs_study.order.repository.SpringDataSagaStepRepository;
import org.springframework.stereotype.Repository;

@Repository
class SagaStepRepositoryAdapter implements SagaStepRepository {

    private final SpringDataSagaStepRepository springDataSagaStepRepository;

    SagaStepRepositoryAdapter(SpringDataSagaStepRepository springDataSagaStepRepository) {
        this.springDataSagaStepRepository = springDataSagaStepRepository;
    }

    @Override
    public SagaStep save(SagaStep sagaStep) {
        return springDataSagaStepRepository.save(sagaStep);
    }

    @Override
    public List<SagaStep> findBySagaId(String sagaId) {
        return springDataSagaStepRepository.findBySagaIdOrderByIdAsc(sagaId);
    }

    @Override
    public Optional<SagaStep> findBySagaIdAndStepName(String sagaId, SagaStepName stepName) {
        return springDataSagaStepRepository.findBySagaIdAndStepName(sagaId, stepName);
    }
}
