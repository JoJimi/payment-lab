package org.example.cs_study.common.outbox;

import java.util.List;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * outbox 테이블을 폴링해 Kafka로 발행하는 유일한 지점이다(로드맵 부록 A-3). 이 클래스
 * 밖에서 {@code KafkaTemplate.send()}를 직접 부르면 안 된다 — 2.10의 Semgrep 커스텀 룰
 * ({@code .semgrep/outbox-required.yml})이 정확히 이 클래스 이름을 예외로 허용한다.
 *
 * <p>발행은 동기로 확인한다({@code .get()}) — ack를 기다리지 않고 바로 PUBLISHED로 표시하면
 * "표시는 됐는데 실제로는 안 나갔다"는 outbox 패턴이 막으려는 바로 그 문제가 재발한다.
 * 발행 실패는 로그만 남기고 그 행은 PENDING에 그대로 둔다 — 다음 폴링에서 재시도된다
 * (Kafka는 at-least-once이므로 같은 이벤트가 두 번 나갈 수 있고, 이 중복은 2.9의 컨슈머
 * 멱등성이 막는다).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.fixed-delay-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : pending) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            // 파티션 키 = aggregateId(=orderId 문자열) — 같은 주문의 이벤트가 같은 파티션에
            // 들어가 순서가 보장된다(docs/architecture/event-catalog.md 공통 규칙).
            kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload()).get();
            event.markPublished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("outbox 발행이 인터럽트됐습니다, 다음 폴링에서 재시도됩니다. outboxId={}", event.getId(), e);
        } catch (ExecutionException e) {
            log.warn("outbox 이벤트 발행 실패, 다음 폴링에서 재시도됩니다. outboxId={}, eventType={}",
                    event.getId(), event.getEventType(), e);
        }
    }
}
