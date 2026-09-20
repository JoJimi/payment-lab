#!/usr/bin/env bash
set -euo pipefail

# 로드맵 1.21 — 베이스라인 측정. 이후 모든 개선(캐싱, 서킷 브레이커, MSA 전환)은 이 숫자와 비교된다.
# 워밍업 1회 + 측정 3회, 중앙값을 benchmarks/03-baseline.md에 직접 기록할 것 (CLAUDE.md 측정 규칙).
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬에서 돌릴 것.
#
# 사용법:
#   docker compose -f docker-compose.yml up -d
#   ./gradlew mockPgRun &
#   ./gradlew bootRun &
#   ./scripts/measure-baseline.sh

PRODUCT_ID=${PRODUCT_ID:-1}
STOCK=${STOCK:-100000} # 베이스라인은 재고 소진이 목적이 아니므로 넉넉하게
VUS=${VUS:-20}
DURATION=${DURATION:-60s}

mkdir -p benchmarks/raw

docker exec payment-lab-postgres psql -U cs -d payment_lab_dev -c \
  "UPDATE inventory SET available = ${STOCK}, reserved = 0 WHERE product_id = ${PRODUCT_ID};"

echo "=== 워밍업 ==="
k6 run --env VUS="${VUS}" --env DURATION=10s --env PRODUCT_ID="${PRODUCT_ID}" k6/order-payment-flow.js >/dev/null

for i in 1 2 3; do
  echo "=== 측정 ${i}/3 ==="
  k6 run \
    --env VUS="${VUS}" --env DURATION="${DURATION}" --env PRODUCT_ID="${PRODUCT_ID}" \
    --summary-export="benchmarks/raw/baseline-run${i}.json" \
    k6/order-payment-flow.js
done

echo "완료. benchmarks/raw/baseline-run*.json 3개의 중앙값을 benchmarks/03-baseline.md에 기록하세요."
