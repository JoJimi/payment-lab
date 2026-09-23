package org.example.cs_study.order.repository.adapter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SpringDataSagaInstanceRepository;
import org.springframework.stereotype.Repository;

@Repository
class SagaInstanceRepositoryAdapter implements SagaInstanceRepository {

    private final SpringDataSagaInstanceRepository springDataSagaInstanceRepository;

    SagaInstanceRepositoryAdapter(SpringDataSagaInstanceRepository springDataSagaInstanceRepository) {
        this.springDataSagaInstanceRepository = springDataSagaInstanceRepository;
    }

    @Override
    public SagaInstance save(SagaInstance sagaInstance) {
        return springDataSagaInstanceRepository.save(sagaInstance);
    }

    @Override
    public Optional<SagaInstance> findById(String sagaId) {
        return springDataSagaInstanceRepository.findById(sagaId);
    }

    @Override
    public Optional<SagaInstance> findByOrderId(Long orderId) {
        return springDataSagaInstanceRepository.findByOrderId(orderId);
    }

    @Override
    public List<SagaInstance> findTimedOutStartedSagas(Instant now) {
        return springDataSagaInstanceRepository.findTop100ByStatusAndTimeoutAtBeforeOrderByTimeoutAtAsc(
                SagaStatus.STARTED, now);
    }
}
