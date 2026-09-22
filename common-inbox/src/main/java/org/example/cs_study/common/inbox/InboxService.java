package org.example.cs_study.common.inbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "이미 처리한 이벤트인지 확인 → 아니면 처리 → 처리 기록"을 한 트랜잭션으로 묶는다.
 * Kafka는 at-least-once라 같은 이벤트가 두 번 올 수 있다(로드맵 2단계 개요) — 이 클래스가
 * 그 중복을 걸러내는 유일한 지점이 되어야 한다.
 *
 * <p>{@link org.example.cs_study.common.outbox.OutboxService}와 달리 이 메서드는 자기
 * 트랜잭션을 스스로 연다(기본 {@code @Transactional}, propagation은 REQUIRED). Outbox 쪽
 * {@code save()}는 이미 진행 중인 비즈니스 요청 트랜잭션에 올라타야 하는 반면(그래서 MANDATORY),
 * Kafka 컨슈머 콜백은 애초에 Spring이 트랜잭션을 열어주지 않는 진입점이라 여기서 직접
 * 경계를 잡아야 한다 — {@code businessLogic}이 DB에 쓰는 작업과 {@code processed_event}
 * insert가 같은 트랜잭션에 묶여야, 처리는 됐는데 기록만 안 남거나 그 반대인 상황이 안 생긴다.
 *
 * <p>존재 확인과 저장 사이에 좁은 TOCTOU 창이 있다 — 정말 동시에 같은 eventId가 두 번
 * 들어오면 둘 다 존재 확인을 통과할 수 있다. Kafka 컨슈머 그룹에서 같은 파티션은 항상 같은
 * 인스턴스가 순차 처리하므로(리밸런싱 시점의 재처리는 순차적으로 일어남) 실제 동시성은
 * 낮다고 판단해 지금은 이 폭을 그대로 둔다 — {@code processed_event.event_id}가 PK라
 * 최악의 경우에도 두 번째 INSERT가 제약 위반으로 실패해 예외로 드러나지, 조용히 씹히지는
 * 않는다.
 */
@Service
public class InboxService {

    private final ProcessedEventRepository processedEventRepository;

    public InboxService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * @return 실제로 처리했으면 true, 이미 처리된 이벤트라 건너뛰었으면 false
     */
    @Transactional
    public boolean processIfNew(String eventId, Runnable businessLogic) {
        if (processedEventRepository.existsById(eventId)) {
            return false;
        }
        businessLogic.run();
        processedEventRepository.save(new ProcessedEvent(eventId));
        return true;
    }
}
