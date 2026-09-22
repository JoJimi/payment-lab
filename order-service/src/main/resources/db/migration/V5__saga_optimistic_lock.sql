-- 2.14: saga_instance/saga_step에 낙관적 락 컬럼을 추가한다(PR #63 CodeRabbit 리뷰에서
-- "동시 쓰기 주체가 없다"는 이유로 반려하며 "2.12가 실제 동시 Kafka 리스너 접근을 들여오면
-- 추가한다"고 약속했던 항목 — 2.13에서 실제로 여러 리스너가 같은 saga_id 행을 건드리게 됐다).
-- Inventory.version(1.11)과 같은 패턴: JPA @Version이 관리하고, UPDATE 시점에 값이
-- 어긋나면 OptimisticLockingFailureException으로 막는다.

ALTER TABLE saga_instance ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE saga_step ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
