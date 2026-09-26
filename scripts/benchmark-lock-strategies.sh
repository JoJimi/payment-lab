#!/usr/bin/env bash
set -euo pipefail

# 로드맵 1.13/3.12 — 재고 락 4종(NONE/PESSIMISTIC/OPTIMISTIC/DISTRIBUTED) 성능 비교.
#
# 2.1(서비스 분리)로 InventoryService가 inventory-service 모듈로 옮겨갔다 — 옛 단일
# CsStudyApplication은 더 이상 존재하지 않는다. inventory-service를 직접 재기동하며
# InventoryController(3.12)의 동기 전용 엔드포인트(k6/inventory-lock-benchmark.js)를
# 두드려 락 전략 자체의 성능만 격리해서 잰다.
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬 Docker 환경에서 돌릴 것.
# 부록 C: "벤치마크 숫자가 노이즈" → CI 러너에서 절대값을 측정하지 말 것.
#
# 사용법:
#   docker compose -f docker-compose.yml up -d
#   set -a && source ./.env && set +a
#   ./scripts/benchmark-lock-strategies.sh
#
# 실행 후 benchmarks/raw/<전략>-run<N>.json의 k6 summary를 읽어
# benchmarks/01-lock-strategies.md 표를 채운다 (워밍업 후 3회 반복, 중앙값 — CLAUDE.md).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_wait-for-app.sh
source "${SCRIPT_DIR}/_wait-for-app.sh"

STRATEGIES=(NONE PESSIMISTIC OPTIMISTIC DISTRIBUTED)
PRODUCT_ID=${PRODUCT_ID:-1}
STOCK=${STOCK:-100}
VUS=${VUS:-50}
DURATION=${DURATION:-30s}
INVENTORY_SERVICE_URL=${INVENTORY_SERVICE_URL:-http://localhost:8083}

mkdir -p benchmarks/raw

reset_inventory() {
  docker exec payment-lab-postgres-inventory psql \
    -U "${DB_USERNAME:?DB_USERNAME이 필요합니다 — .env를 source 하세요}" \
    -d payment_lab_inventory -v ON_ERROR_STOP=1 -Atq -c \
    "UPDATE inventory SET available = ${STOCK}, reserved = 0 WHERE product_id = ${PRODUCT_ID};"
}

for STRATEGY in "${STRATEGIES[@]}"; do
  echo "=== ${STRATEGY} ==="

  # 전략 전환은 재기동으로만 한다 (CLAUDE.md: 한 번에 한 개념만 켠다 — 핫스위치 없음).
  pkill -f 'org.example.cs_study.inventory.InventoryServiceApplication' 2>/dev/null || true
  sleep 2
  ./gradlew inventory-service:bootRun --args="--inventory.lock-strategy=${STRATEGY}" &
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
    # 워밍업/이전 측정이 재고를 소진시키므로 매 측정 전 동일 조건으로 재초기화한다.
    reset_inventory

    echo "--- 측정 ${i}/3 ---"
    k6 run \
      --env VUS="${VUS}" --env DURATION="${DURATION}" --env PRODUCT_ID="${PRODUCT_ID}" \
      --env INVENTORY_SERVICE_URL="${INVENTORY_SERVICE_URL}" \
      --summary-export="benchmarks/raw/${STRATEGY}-run${i}.json" \
      k6/inventory-lock-benchmark.js
  done

  kill "${APP_PID}" 2>/dev/null || true
  sleep 2
done

echo "완료. benchmarks/raw/<전략>-run*.json(3회)의 중앙값을 benchmarks/01-lock-strategies.md 표에 채우세요."
