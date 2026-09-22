-- 2.9: 컨슈머 멱등성 (로드맵 2단계 개요 — Kafka는 at-least-once이므로 중복은 반드시 온다).
-- event_id를 PK로 직접 써서 별도 유니크 제약 없이도 동시 중복 INSERT를 막는다.
-- payment-service는 payment.requested를 구독한다(docs/architecture/event-catalog.md).
CREATE TABLE processed_event (
    event_id      VARCHAR(36)  PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
