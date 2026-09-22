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
 * <p>중복 선점은 {@link ProcessedEventRepository#insertIfAbsent}의 원자적 INSERT로 한다.
 * 처음엔 "{@code existsById} 확인 후 {@code save}" 방식이었는데, 두 트랜잭션이 정말 동시에
 * 같은 eventId로 들어오면 둘 다 확인을 통과해 비즈니스 로직이 두 번 실행될 수 있었다
 * (CodeRabbit PR #60 리뷰 — TOCTOU). DB의 유니크 제약(PK)에 선점을 맡기면 두 트랜잭션 중
 * 정확히 하나만 삽입에 성공하므로 이 창이 사라진다.
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
        if (processedEventRepository.insertIfAbsent(eventId) == 0) {
            return false;
        }
        businessLogic.run();
        return true;
    }
}
