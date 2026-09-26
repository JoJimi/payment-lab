#!/usr/bin/env bash
set -euo pipefail

# 로드맵 1.14/3.12 — 낙관적 락 재시도 횟수(1/3/5/10)별 경합 성능 곡선.
# 1.13(benchmark-lock-strategies.sh)과 같은 k6 시나리오를 재사용하되, inventory.lock-strategy는
# OPTIMISTIC으로 고정하고 inventory.optimistic-lock.max-retries만 바꿔가며 측정한다
# (한 번에 한 개념만 켠다). inventory-service를 직접 재기동하는 이유는
# benchmark-lock-strategies.sh 상단 주석 참고 — 2.1 이후 InventoryService가 이 모듈로
# 옮겨갔고, 락 전략 성능은 InventoryController(3.12)의 동기 엔드포인트로만 격리해서 잴 수 있다.
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬 Docker 환경에서 돌릴 것.
#
# 사용법:
#   docker compose -f docker-compose.yml up -d
#   set -a && source ./.env && set +a
#   ./scripts/benchmark-optimistic-retries.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_wait-for-app.sh
source "${SCRIPT_DIR}/_wait-for-app.sh"

RETRY_COUNTS=(1 3 5 10)
PRODUCT_ID=${PRODUCT_ID:-1}
# 재고 소진 자체가 목적이 아니다 — benchmark-lock-strategies.sh와 같은 이유로 넉넉하게 잡는다
# (CodeRabbit 리뷰, PR #97).
STOCK=${STOCK:-100000}
VUS=${VUS:-50}
DURATION=${DURATION:-30s}
INVENTORY_SERVICE_URL=${INVENTORY_SERVICE_URL:-http://localhost:8083}

mkdir -p benchmarks/raw

# CodeRabbit 리뷰(PR #97) — benchmark-lock-strategies.sh와 동일한 이유로 RETURNING 확인.
reset_inventory() {
  local updated_product_id
  updated_product_id="$(
    docker exec payment-lab-postgres-inventory psql \
      -U "${DB_USERNAME:?DB_USERNAME이 필요합니다 — .env를 source 하세요}" \
      -d payment_lab_inventory -v ON_ERROR_STOP=1 -Atq -c \
      "UPDATE inventory SET available = ${STOCK}, reserved = 0
       WHERE product_id = ${PRODUCT_ID}
       RETURNING product_id;"
  )"
  if [[ "${updated_product_id}" != "${PRODUCT_ID}" ]]; then
    echo "inventory 행이 없습니다: product_id=${PRODUCT_ID}" >&2
    exit 1
  fi
}

for RETRIES in "${RETRY_COUNTS[@]}"; do
  echo "=== max-retries=${RETRIES} ==="

  pkill -f 'org.example.cs_study.inventory.InventoryServiceApplication' 2>/dev/null || true
  sleep 2
  ./gradlew inventory-service:bootRun \
    --args="--inventory.lock-strategy=OPTIMISTIC --inventory.optimistic-lock.max-retries=${RETRIES}" &
  APP_PID=$!
  # set -e라 wait_for_app_ready가 타임아웃(exit 1)하면 아래 kill에 못 미치고 스크립트가
  # 곧장 끝난다 — bootRun 프로세스가 백그라운드에 남는 걸 막기 위해 EXIT 트랩으로 대비한다.
  trap 'kill "${APP_PID}" 2>/dev/null || true' EXIT
  wait_for_app_ready "${INVENTORY_SERVICE_URL}/actuator/health"

  reset_inventory
  echo "--- 워밍업 ---"
  k6 run --env VUS="${VUS}" --env DURATION=10s --env PRODUCT_ID="${PRODUCT_ID}" \
    --env INVENTORY_SERVICE_URL="${INVENTORY_SERVICE_URL}" \
    k6/inventory-lock-benchmark.js >/dev/null

  for i in 1 2 3; do
    reset_inventory

    echo "--- 측정 ${i}/3 ---"
    k6 run \
      --env VUS="${VUS}" --env DURATION="${DURATION}" --env PRODUCT_ID="${PRODUCT_ID}" \
      --env INVENTORY_SERVICE_URL="${INVENTORY_SERVICE_URL}" \
      --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
      --summary-export="benchmarks/raw/optimistic-retries-${RETRIES}-run${i}.json" \
      k6/inventory-lock-benchmark.js
  done

  kill "${APP_PID}" 2>/dev/null || true
  sleep 2
done

echo "완료. benchmarks/raw/optimistic-retries-*-run*.json(3회)의 중앙값을 benchmarks/02-optimistic-retry-curve.md 표에 채우세요."
