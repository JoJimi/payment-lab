-- 2.2: 서비스별 DB 분리. payment-service DB는 payments/idempotency_keys 테이블을 소유한다.
-- (2.1까지는 3개 서비스가 같은 DB를 공유해 products/inventory/orders까지 전부 들어있었다 —
-- 이제 각자 자신의 도메인 테이블만 갖는다. docs/troubleshooting/04-msa-split.md #10 참고)
--
-- orders/payments는 서로 FK를 걸지 않는다. DB가 물리적으로 분리됐으므로 DB 레벨 참조
-- 무결성은 애플리케이션에서만 보장한다 (소프트 참조).

CREATE TABLE payments (
    id               BIGSERIAL     PRIMARY KEY,
    order_id         BIGINT        NOT NULL,      -- order-service DB orders.id 소프트 참조 (FK 없음, 다른 DB)
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

-- 서로 다른 멱등키로 같은 주문에 동시에 결제를 두 번 진행할 수 없게 막는 원자적 방어.
-- PaymentService.validateOrder()는 읽기 시점 검사라 두 동시 요청 모두 통과할 수 있다(TOCTOU) —
-- 이 부분 유니크 인덱스가 진짜 경합 시 INSERT 단계에서 하나만 통과시킨다. FAILED/CANCELLED는
-- 종결 상태라 재시도로 새 결제를 다시 붙일 수 있어야 하므로 제외한다.
CREATE UNIQUE INDEX ux_payments_active_order ON payments (order_id)
    WHERE status IN ('PENDING', 'APPROVED', 'UNKNOWN');

-- @Idempotent AOP의 2차 방어 (부록 A-1). Redis SETNX가 1차(TTL 10분), 이 테이블이
-- 2차(TTL 24시간, expires_at으로 관리)다. 도메인 중립 테이블(common-idempotency 모듈 소유) —
-- 현재 유일한 소비자인 payment-service의 DB에 둔다.
CREATE TABLE idempotency_keys (
    id                   BIGSERIAL    PRIMARY KEY,
    idempotency_key      VARCHAR(255) NOT NULL UNIQUE,
    status               VARCHAR(20)  NOT NULL,   -- IN_PROGRESS/COMPLETED
    -- 같은 키로 다른 요청 본문(주문/금액/통화 등)이 들어오면 그냥 재현하지 않고 409로 거부하기
    -- 위한 지문(SHA-256 hex, 64자). 최초 요청에서 계산해 저장하고, 같은 키 재사용 때마다 비교한다.
    request_fingerprint  VARCHAR(64)  NOT NULL,
    response_status      INT,
    response_body        TEXT,                    -- 원본 응답 그대로 저장 (재현용, 409 아님)
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ  NOT NULL
);

-- Transactional Outbox (부록 A-3). 컬럼만 정의, 2-B(2.8)에서 실제 사용.
-- payment-service가 payment.requested/payment.completed/payment.failed를 여기서 발행한다.
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
