package org.example.cs_study.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSagaInstanceRepository extends JpaRepository<SagaInstance, String> {

    Optional<SagaInstance> findByOrderId(Long orderId);

    List<SagaInstance> findByStatusAndTimeoutAtBefore(SagaStatus status, Instant timeoutAt);
}
