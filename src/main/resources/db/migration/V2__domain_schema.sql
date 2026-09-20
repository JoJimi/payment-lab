-- 1단계: 도메인 스키마 (docs/domain/erd.md, docs/domain/state-transitions.md 참고)
--
-- orders/payments는 서로 FK를 걸지 않는다. 2단계에서 DB가 분리되므로
-- DB 레벨 참조 무결성은 애플리케이션에서만 보장한다 (소프트 참조).

CREATE TABLE products (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(255)  NOT NULL,
    price       NUMERIC(19,4) NOT NULL CHECK (price >= 0),
    currency    VARCHAR(3)    NOT NULL DEFAULT 'KRW',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE inventory (
    id          BIGSERIAL   PRIMARY KEY,
    product_id  BIGINT      NOT NULL UNIQUE,
    available   INT         NOT NULL CHECK (available >= 0),
    reserved    INT         NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    -- 낙관적 락(@Version, 1.11)용 컬럼. 비관적/분산락 모드에서도 컬럼은 유지하되 검사하지 않는다.
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id           BIGSERIAL     PRIMARY KEY,
    product_id   BIGINT        NOT NULL,      -- products.id 소프트 참조 (FK 없음)
    quantity     INT           NOT NULL CHECK (quantity > 0),
    status       VARCHAR(20)   NOT NULL,       -- CREATED/PAID/FAILED/CANCELLED
    total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount >= 0),
    currency     VARCHAR(3)    NOT NULL DEFAULT 'KRW',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_product_id ON orders (product_id);

CREATE TABLE payments (
    id               BIGSERIAL     PRIMARY KEY,
    order_id         BIGINT        NOT NULL,      -- orders.id 소프트 참조 (FK 없음)
    idempotency_key  VARCHAR(255)  NOT NULL UNIQUE,
    status           VARCHAR(20)   NOT NULL,       -- PENDING/APPROVED/FAILED/UNKNOWN/CANCELLED
    amount           NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
    currency         VARCHAR(3)    NOT NULL DEFAULT 'KRW',
    pg_transaction_id VARCHAR(255),
    requested_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    approved_at      TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_order_id ON payments (order_id);

-- @Idempotent AOP의 2차 방어 (부록 A-1). Redis SETNX가 1차(TTL 10분), 이 테이블이
-- 2차(TTL 24시간, expires_at으로 관리)다. 도메인 중립 테이블 — 결제 외 엔드포인트에도 재사용 가능.
CREATE TABLE idempotency_keys (
    id               BIGSERIAL    PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    status           VARCHAR(20)  NOT NULL,   -- IN_PROGRESS/COMPLETED
    response_status  INT,
    response_body    TEXT,                    -- 원본 응답 그대로 저장 (재현용, 409 아님)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL
);

-- Transactional Outbox (부록 A-3). 컬럼만 정의, 2단계부터 실제 사용.
CREATE TABLE outbox (
    id             BIGSERIAL    PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING/PUBLISHED
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status ON outbox (status);
