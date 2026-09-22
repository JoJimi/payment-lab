-- 2.11: Saga 오케스트레이션 상태 테이블 (Order Service가 조율자, 부록 A-2).
-- saga_id는 UUID로 애플리케이션에서 발급한다 — order_id를 그대로 PK로 쓰면 이후(3-4단계
-- 이후) 같은 주문에 대한 재시도 Saga가 필요해질 때 식별자를 다시 설계해야 한다. 지금은
-- 주문 1건당 Saga 1개만 허용하므로 order_id에 유니크 제약을 걸어 그 불변식을 DB가 보장하게
-- 한다.

CREATE TABLE saga_instance (
    saga_id      VARCHAR(36)  PRIMARY KEY,
    order_id     BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,  -- STARTED/COMPENSATING/COMPLETED/FAILED
    current_step VARCHAR(20),            -- PAYMENT/INVENTORY/NOTIFICATION, 시작 전엔 NULL
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    timeout_at   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX idx_saga_instance_order_id ON saga_instance (order_id);

-- 2.15 타임아웃 스케줄러가 'WHERE status = STARTED AND timeout_at < now()'로 조회한다.
CREATE INDEX idx_saga_instance_status_timeout ON saga_instance (status, timeout_at);

CREATE TABLE saga_step (
    id               BIGSERIAL    PRIMARY KEY,
    saga_id          VARCHAR(36)  NOT NULL REFERENCES saga_instance (saga_id),
    step_name        VARCHAR(20)  NOT NULL,  -- PAYMENT/INVENTORY/NOTIFICATION
    status           VARCHAR(20)  NOT NULL,  -- PENDING/SUCCESS/FAILED/COMPENSATED
    request_payload  TEXT,
    response_payload TEXT,
    attempted_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_saga_step_saga_id ON saga_step (saga_id);
