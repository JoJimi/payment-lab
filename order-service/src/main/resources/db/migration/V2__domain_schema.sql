-- 2.2: 서비스별 DB 분리. order-service DB는 orders 테이블만 소유한다.
-- (2.1까지는 3개 서비스가 같은 DB를 공유해 products/inventory/payments/idempotency_keys까지
-- 전부 들어있었다 — 이제 각자 자신의 도메인 테이블만 갖는다. docs/troubleshooting/04-msa-split.md #10 참고)
--
-- orders/payments는 서로 FK를 걸지 않는다. DB가 물리적으로 분리됐으므로 DB 레벨 참조
-- 무결성은 애플리케이션에서만 보장한다 (소프트 참조).

CREATE TABLE orders (
    id           BIGSERIAL     PRIMARY KEY,
    product_id   BIGINT        NOT NULL,      -- inventory-service DB products.id 소프트 참조 (FK 없음, 다른 DB)
    quantity     INT           NOT NULL CHECK (quantity > 0),
    status       VARCHAR(20)   NOT NULL,       -- CREATED/PAID/FAILED/CANCELLED
    total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount >= 0),
    currency     VARCHAR(3)    NOT NULL DEFAULT 'KRW',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_product_id ON orders (product_id);

-- Transactional Outbox (부록 A-3). 컬럼만 정의, 2-B(2.8)에서 실제 사용.
-- Order Service가 Saga 오케스트레이터라 order.created/order.cancelled를 여기서 발행한다.
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
