#!/usr/bin/env bash
set -euo pipefail

# 로드맵 3.12 — 캐시 유무별 성능 비교. ProductController가 같은 조회를 두 경로로 노출한다 —
# /api/products/{id}(캐시 있음, ProductService.getProduct, 1.15) vs
# /api/products/{id}/uncached(캐시 없음, getProductUnprotected, 1.17 대조군을 3.12에서 HTTP로 노출).
# 두 경로가 같은 애플리케이션에 동시에 떠 있으므로 락 전략 비교와 달리 재기동 없이 잴 수 있다.
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬 Docker 환경에서 돌릴 것.
#
# 사용법:
#   docker compose -f docker-compose.yml up -d
#   ./gradlew inventory-service:bootRun &
#   ./scripts/benchmark-cache.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_wait-for-app.sh
source "${SCRIPT_DIR}/_wait-for-app.sh"

PRODUCT_ID=${PRODUCT_ID:-1}
VUS=${VUS:-50}
DURATION=${DURATION:-30s}
INVENTORY_SERVICE_URL=${INVENTORY_SERVICE_URL:-http://localhost:8083}

mkdir -p benchmarks/raw

wait_for_app_ready "${INVENTORY_SERVICE_URL}/actuator/health"

# cached/uncached 각각 워밍업 1회 + 측정 3회, 중앙값 사용 (CLAUDE.md 측정 규칙).
# cached 워밍업은 sync=true 캐시를 미리 채워, 측정 구간에 첫 캐시 미스가 섞이지 않게 한다.
run_mode() {
  local mode=$1
  echo "=== [${mode}] 워밍업 ==="
  k6 run --env MODE="${mode}" --env VUS="${VUS}" --env DURATION=10s --env PRODUCT_ID="${PRODUCT_ID}" \
    --env INVENTORY_SERVICE_URL="${INVENTORY_SERVICE_URL}" \
    k6/cache-benchmark.js >/dev/null

  for i in 1 2 3; do
    echo "=== [${mode}] 측정 ${i}/3 ==="
    k6 run --env MODE="${mode}" --env VUS="${VUS}" --env DURATION="${DURATION}" --env PRODUCT_ID="${PRODUCT_ID}" \
      --env INVENTORY_SERVICE_URL="${INVENTORY_SERVICE_URL}" \
      --summary-export="benchmarks/raw/cache-${mode}-run${i}.json" \
      k6/cache-benchmark.js
  done
}

run_mode uncached
run_mode cached

echo "완료. benchmarks/raw/cache-{uncached,cached}-run*.json(각 3회)의 중앙값을 benchmarks/06-cache.md 표에 채우세요."
