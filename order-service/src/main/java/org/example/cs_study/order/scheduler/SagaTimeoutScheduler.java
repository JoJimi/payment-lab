package org.example.cs_study.order.scheduler;

import java.time.Instant;
import java.util.List;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.service.SagaTimeoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Saga 타임아웃 회수(2.15) — {@code payment.failed}/{@code inventory.failed} 같은 실패
 * 이벤트가 아예 오지 않는 상황(다운스트림 서비스가 죽었거나 Kafka 메시지가 유실되는 등)에
 * 대비한 마지막 방어선이다. {@code saga_instance.timeout_at}을 넘긴 채 여전히
 * {@code STARTED}인 Saga를 주기적으로 찾아 {@link SagaTimeoutService#reclaim}으로 넘긴다.
 *
 * <p>{@code @EnableScheduling}은 common-outbox 모듈의 {@code OutboxSchedulingConfig}가 이미
 * 켜뒀다(order-service가 그 모듈을 의존).
 *
 * <p>Saga 하나를 회수하다 실패해도 나머지를 계속 시도한다 — {@link SagaTimeoutService#reclaim}을
 * Saga별로 각자 트랜잭션을 여는 메서드로 분리해뒀기 때문에(이 클래스 자체는
 * {@code @Transactional}이 아니다) 하나가 예외를 던져도 다른 Saga의 회수를 막지 않는다.
 */
@Component
public class SagaTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutScheduler.class);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaTimeoutService sagaTimeoutService;

    public SagaTimeoutScheduler(SagaInstanceRepository sagaInstanceRepository, SagaTimeoutService sagaTimeoutService) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaTimeoutService = sagaTimeoutService;
    }

    @Scheduled(fixedDelayString = "${app.saga.timeout.scheduler.fixed-delay-ms:30000}")
    public void reclaimTimedOutSagas() {
        List<SagaInstance> timedOut = sagaInstanceRepository.findTimedOutStartedSagas(Instant.now());
        for (SagaInstance sagaInstance : timedOut) {
            try {
                sagaTimeoutService.reclaim(sagaInstance.getSagaId());
            } catch (RuntimeException e) {
                log.error("Saga 타임아웃 회수 실패, 다음 폴링에서 재시도됩니다. sagaId={}", sagaInstance.getSagaId(), e);
            }
        }
    }
}
