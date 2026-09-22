package org.example.cs_study.event;

import java.time.Instant;

/**
 * 2.1/2.7 — 서비스 간 Kafka 이벤트 공통 봉투(envelope). 실제 페이로드 타입(order.created,
 * payment.completed 등)은 2.6~2.7에서 정의한다. 지금은 멀티모듈 전환의 일부로 계약 스켈레톤만
 * 둔다.
 *
 * <p>{@code traceId}를 굳이 싣는 이유: MDC는 ThreadLocal이라 Kafka 컨슈머 스레드에서 끊긴다.
 * 봉투에 실어 나르고 컨슈머에서 MDC에 복원해야 traceId 기반 분산 로그 조회(4.4)가 된다.
 *
 * <p>Jackson 3(Boot 4)는 프로퍼티를 기본 알파벳 순으로 직렬화한다. 필드 순서가 중요해지면
 * {@code @JsonPropertyOrder}를 명시할 것(로드맵 2단계 개요 참고).
 *
 * @param eventId    이벤트 고유 ID (UUID)
 * @param eventType  이벤트 종류 (예: "order.created")
 * @param version    페이로드 스키마 버전
 * @param occurredAt 이벤트 발생 시각
 * @param traceId    발행 시점의 분산 추적 ID
 * @param payload    실제 이벤트 데이터
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int version,
        Instant occurredAt,
        String traceId,
        T payload) {
}
