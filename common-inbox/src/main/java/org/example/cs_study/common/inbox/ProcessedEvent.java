package org.example.cs_study.common.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 컨슈머가 이미 처리한 이벤트의 {@link org.example.cs_study.event.EventEnvelope#eventId()}를
 * 기록한다. Kafka는 at-least-once라 같은 이벤트가 두 번 올 수 있다(로드맵 2단계 개요) —
 * 이 테이블에 존재하면 재처리하지 않고 건너뛴다({@link InboxService} 참고).
 *
 * <p>{@code eventId}를 PK로 직접 써서 유니크 제약을 별도로 걸 필요가 없게 했다 — 동시에
 * 같은 이벤트가 두 번 들어와도 두 번째 INSERT가 PK 충돌로 막힌다(경쟁 상태 방어).
 */
@Entity
@Table(name = "processed_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    ProcessedEvent(String eventId) {
        this.eventId = eventId;
    }

    @PrePersist
    void onCreate() {
        this.processedAt = Instant.now();
    }
}
