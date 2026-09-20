#!/usr/bin/env bash
set -euo pipefail

# 로드맵 1.14 — 낙관적 락 재시도 횟수(1/3/5/10)별 경합 성능 곡선.
# 1.13과 같은 k6 시나리오를 재사용하되, inventory.lock-strategy는 OPTIMISTIC으로 고정하고
# inventory.optimistic-lock.max-retries만 바꿔가며 측정한다 (한 번에 한 개념만 켠다).
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬 Docker 환경에서 돌릴 것.
#
# 사용법:
#   docker compose -f docker-compose.yml up -d
#   ./gradlew mockPgRun &
#   ./scripts/benchmark-optimistic-retries.sh

RETRY_COUNTS=(1 3 5 10)
PRODUCT_ID=${PRODUCT_ID:-1}
STOCK=${STOCK:-100}
VUS=${VUS:-50}
DURATION=${DURATION:-30s}

mkdir -p benchmarks/raw

for RETRIES in "${RETRY_COUNTS[@]}"; do
  echo "=== max-retries=${RETRIES} ==="

  docker exec payment-lab-postgres psql -U cs -d payment_lab_dev -c \
    "UPDATE inventory SET available = ${STOCK}, reserved = 0 WHERE product_id = ${PRODUCT_ID};"

  pkill -f 'org.example.cs_study.CsStudyApplication' 2>/dev/null || true
  sleep 2
  ./gradlew bootRun --args="--inventory.lock-strategy=OPTIMISTIC --inventory.optimistic-lock.max-retries=${RETRIES}" &
  APP_PID=$!
  sleep 15

  k6 run \
    --env VUS="${VUS}" --env DURATION="${DURATION}" --env PRODUCT_ID="${PRODUCT_ID}" \
    --summary-export="benchmarks/raw/optimistic-retries-${RETRIES}.json" \
    k6/order-lock-benchmark.js

  kill "${APP_PID}" 2>/dev/null || true
  sleep 2
done

echo "완료. benchmarks/raw/optimistic-retries-*.json을 읽어 benchmarks/02-optimistic-retry-curve.md 표를 채우세요."
