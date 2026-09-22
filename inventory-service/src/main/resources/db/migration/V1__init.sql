-- 0단계: 초기 스키마
-- 도메인 테이블은 1단계에서 추가됩니다.
-- gen_random_uuid()는 PostgreSQL 13+ 내장 함수 (uuid-ossp 확장 불필요)

CREATE TABLE IF NOT EXISTS schema_migration_log (
    id          BIGSERIAL    PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    applied_at  TIMESTAMPTZ  DEFAULT NOW()
);

INSERT INTO schema_migration_log (description) VALUES ('Phase 0: initial schema setup');
