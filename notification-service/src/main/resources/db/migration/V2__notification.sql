-- 2.12: notification-service의 첫 도메인 테이블. inventory-service와 이름이 부딪히지 않는
-- 새 테이블이라 IF NOT EXISTS가 필요 없다.
CREATE TABLE notification (
    id         BIGSERIAL    PRIMARY KEY,
    order_id   BIGINT       NOT NULL,   -- order-service DB orders.id 소프트 참조 (FK 없음, 다른 DB)
    type       VARCHAR(20)  NOT NULL,   -- ORDER_COMPLETED/ORDER_CANCELLED
    message    TEXT         NOT NULL,
    sent_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_order_id ON notification (order_id);
