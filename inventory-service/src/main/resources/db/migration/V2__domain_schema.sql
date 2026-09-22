-- 2.2: 서비스별 DB 분리. inventory-service DB는 products/inventory 테이블을 소유한다.
-- (2.1까지는 3개 서비스가 같은 DB를 공유해 orders/payments/idempotency_keys까지 전부 들어있었다 —
-- 이제 각자 자신의 도메인 테이블만 갖는다. docs/troubleshooting/04-msa-split.md #10 참고)
--
-- README 방침상 이 DB는 notification-service가 엔티티를 갖게 되면 함께 공유한다
-- (notification-service는 아직 영속 대상이 없어 이 DB에 접속하지 않는다).

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

-- Transactional Outbox (부록 A-3). 컬럼만 정의, 2-B(2.8)에서 실제 사용.
-- inventory-service가 inventory.reserved/inventory.failed를 여기서 발행한다.
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
