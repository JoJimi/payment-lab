-- 2.9: 컨슈머 멱등성 (로드맵 2단계 개요 — Kafka는 at-least-once이므로 중복은 반드시 온다).
-- event_id를 PK로 직접 써서 별도 유니크 제약 없이도 동시 중복 INSERT를 막는다.
-- inventory-service는 payment.completed를 구독한다(docs/architecture/event-catalog.md).
-- 2.12: notification-service와 같은 물리 DB를 공유하고(docker-compose.yml), 두 앱이 호스트에서
-- 독립적으로 bootRun되어 시작 순서가 보장되지 않는다 — notification-service의 V3가 먼저
-- 돌면 이 테이블이 이미 있을 수 있으므로 IF NOT EXISTS로 방어한다(notification-service의
-- V3__processed_event.sql과 같은 이유, V1__init.sql과 동일 패턴).
CREATE TABLE IF NOT EXISTS processed_event (
    event_id      VARCHAR(36)  PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
