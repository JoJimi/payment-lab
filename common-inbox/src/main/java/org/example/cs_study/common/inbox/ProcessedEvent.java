package org.example.cs_study.common.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * <p>실제 insert는 {@link ProcessedEventRepository#insertIfAbsent}의 원자적 네이티브 쿼리로
 * 이뤄진다(CodeRabbit PR #60 리뷰 — existsById 후 save하는 방식은 동시 중복 요청에서 TOCTOU가
 * 있었다) — 그래서 이 엔티티는 생성자/`@PrePersist`가 없다. JPA를 거쳐 만들어지는 레코드가
 * 아니라, 조회(`existsById` 등 디버깅/조회 용도)를 위한 읽기 전용 매핑이다.
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
}
