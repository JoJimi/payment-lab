-- 2.12: notification-service의 첫 마이그레이션. README 방침(2.2)대로 inventory-service와
-- 물리 DB를 공유하므로(application.yml의 별도 flyway.table 참고), inventory-service의
-- V1__init.sql이 이미 만들어뒀을 수 있는 테이블과 부딪히지 않도록 IF NOT EXISTS를 쓴다 —
-- 다른 서비스들의 V1__init.sql(각자 전용 DB라 이 걱정이 없다)과 이 파일만 다른 이유다.
CREATE TABLE IF NOT EXISTS schema_migration_log (
    id          BIGSERIAL    PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    applied_at  TIMESTAMPTZ  DEFAULT NOW()
);

INSERT INTO schema_migration_log (description) VALUES ('notification-service: 공유 DB(payment_lab_inventory)에 합류');
