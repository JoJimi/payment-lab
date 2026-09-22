package org.example.cs_study.common.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * {@code event_id}가 아직 없을 때만 삽입한다(원자적 선점). 두 트랜잭션이 동시에
     * 같은 eventId로 이 메서드를 불러도 DB의 유니크 제약(PK)이 정확히 하나만 통과시킨다 —
     * "확인 후 저장" 방식의 TOCTOU 창을 없앤다(CodeRabbit PR #60 리뷰).
     *
     * @return 실제로 삽입됐으면 1, 이미 존재해 건너뛰었으면 0
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO processed_event (event_id, processed_at)
                    VALUES (:eventId, now())
                    ON CONFLICT (event_id) DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId);
}
