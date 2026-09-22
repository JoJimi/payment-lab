-- 2.12: 컨슈머 멱등성(2.9와 같은 스키마). inventory-service가 이미 이 테이블을 만들어뒀을
-- 수 있는 공유 DB라 IF NOT EXISTS를 쓴다(V1__init.sql과 같은 이유) — event_id는 전역
-- UUID라 두 서비스가 물리적으로 같은 테이블을 나눠 써도 값이 겹치지 않는다.
CREATE TABLE IF NOT EXISTS processed_event (
    event_id      VARCHAR(36)  PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
