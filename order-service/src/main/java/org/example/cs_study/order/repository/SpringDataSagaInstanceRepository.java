package org.example.cs_study.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSagaInstanceRepository extends JpaRepository<SagaInstance, String> {

    Optional<SagaInstance> findByOrderId(Long orderId);

    // OutboxEventRepository.findTop100ByStatusOrderByIdAsc와 같은 이유(2.10) — 다운스트림
    // 장애가 길어져 만료된 Saga가 대량으로 쌓여도 한 폴링에서 전부 메모리에 올리지 않는다
    // (CodeRabbit 리뷰, PR #71). 가장 오래 지연된 것부터 처리한다.
    List<SagaInstance> findTop100ByStatusAndTimeoutAtBeforeOrderByTimeoutAtAsc(SagaStatus status, Instant timeoutAt);
}
