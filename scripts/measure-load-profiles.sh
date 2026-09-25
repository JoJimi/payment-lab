#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_wait-for-app.sh
source "${SCRIPT_DIR}/_wait-for-app.sh"

# 로드맵 3.11 — k6/saga-order-flow.js(3.10)의 4개 PROFILE(smoke/load/stress/spike)을
# 스크립트 한 번으로 전부 실행해 결과를 benchmarks/raw/*.json으로 떨어뜨린다.
#
# smoke/load는 2.20/3.6과 같은 원칙으로 "이 회차가 유효한가"를 확인하는 목적이라
# 워밍업 후 3회 반복해 중앙값을 비교할 수 있게 한다(measure-saga-baseline.sh와 동일 패턴).
# stress/spike는 반대로 단일 실행 안에서 VU가 계단식/스파이크로 변하며 만드는
# "시간에 따른 변화 곡선" 자체가 결과이므로, 반복 실행이 아니라 1회만 돈다.
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬에서 돌릴 것.
#
# 사용법:
#   docker compose -f docker-compose.yml up -d
#   ./gradlew mock-pg-server:run &
#   ./gradlew order-service:bootRun &
#   ./gradlew payment-service:bootRun &
#   ./gradlew inventory-service:bootRun &
#   ./gradlew notification-service:bootRun &
#   ./scripts/measure-load-profiles.sh

PRODUCT_ID=${PRODUCT_ID:-1}
STOCK=${STOCK:-100000} # 4개 프로파일 전부 합쳐도 소진되지 않게 넉넉하게
UNIT_PRICE=${UNIT_PRICE:-10000}
CURRENCY=${CURRENCY:-KRW}
VUS=${VUS:-20}
DURATION=${DURATION:-60s}
STRESS_MAX_VUS=${STRESS_MAX_VUS:-200}
SPIKE_BASE_VUS=${SPIKE_BASE_VUS:-10}
SPIKE_MAX_VUS=${SPIKE_MAX_VUS:-300}
ORDER_SERVICE_URL=${ORDER_SERVICE_URL:-http://localhost:8081}
INVENTORY_SERVICE_URL=${INVENTORY_SERVICE_URL:-http://localhost:8083}

mkdir -p benchmarks/raw

wait_for_app_ready "${INVENTORY_SERVICE_URL}/actuator/health"

# measure-saga-baseline.sh와 같은 이유 — Flyway 마이그레이션이 끝나고 readiness가 UP인
# 뒤에야 이 UPDATE가 안전하다. RETURNING으로 실제 갱신 행을 확인해 없으면 즉시 종료한다.
updated_product_id="$(
  docker exec payment-lab-postgres-inventory psql \
    -U "${DB_USERNAME:?DB_USERNAME이 필요합니다 — .env를 source 하세요}" \
    -d payment_lab_inventory -v ON_ERROR_STOP=1 -Atq -c \
    "UPDATE inventory SET available = ${STOCK}, reserved = 0
     WHERE product_id = ${PRODUCT_ID}
     RETURNING product_id;"
)"
if [[ "${updated_product_id}" != "${PRODUCT_ID}" ]]; then
  echo "inventory 행이 없습니다: product_id=${PRODUCT_ID} (products/inventory에 먼저 상품을 등록하세요)" >&2
  exit 1
fi

wait_for_app_ready "${ORDER_SERVICE_URL}/actuator/health"

# k6/saga-order-flow.js를 공통 환경변수로 실행한다. profile 뒤에 넘긴 나머지 인자는
# 그대로 k6 run에 전달되므로, 호출부에서 --summary-export/--out 등을 자유롭게 얹는다.
run_k6() {
  local profile=$1
  shift
  k6 run \
    --env PROFILE="${profile}" --env PRODUCT_ID="${PRODUCT_ID}" \
    --env UNIT_PRICE="${UNIT_PRICE}" --env CURRENCY="${CURRENCY}" \
    --env ORDER_SERVICE_URL="${ORDER_SERVICE_URL}" \
    --env STRESS_MAX_VUS="${STRESS_MAX_VUS}" \
    --env SPIKE_BASE_VUS="${SPIKE_BASE_VUS}" --env SPIKE_MAX_VUS="${SPIKE_MAX_VUS}" \
    "$@" \
    k6/saga-order-flow.js
}

echo "=== [smoke] 스크립트/환경 sanity check ==="
run_k6 smoke --summary-export="benchmarks/raw/smoke.json"

echo "=== [load] 워밍업 ==="
run_k6 load --env DURATION=10s >/dev/null

for i in 1 2 3; do
  echo "=== [load] 측정 ${i}/3 ==="
  run_k6 load --env VUS="${VUS}" --env DURATION="${DURATION}" \
    --summary-export="benchmarks/raw/load-run${i}.json"
done

echo "=== [stress] 0 -> ${STRESS_MAX_VUS} VU 계단식 램프업 ==="
# CodeRabbit 리뷰 — --summary-export는 실행 전체가 끝난 뒤의 집계값 하나만 남긴다.
# stress/spike는 그 집계값이 아니라 "VU가 바뀌면서 지표가 어떻게 변하는가"라는 시간에
# 따른 곡선 자체가 보고 싶은 결과이므로, --out json=...으로 요청/지표 단위 원시 데이터
# 포인트를 같이 남겨야 나중에(3.12) 그 곡선을 복원할 수 있다.
run_k6 stress --summary-export="benchmarks/raw/stress.json" \
  --out json="benchmarks/raw/stress-points.json"

echo "=== [spike] ${SPIKE_BASE_VUS} -> ${SPIKE_MAX_VUS} VU 급증/복귀 ==="
run_k6 spike --summary-export="benchmarks/raw/spike.json" \
  --out json="benchmarks/raw/spike-points.json"

echo ""
echo "완료. benchmarks/raw/ 에 다음 파일이 생성됨:"
echo "  smoke.json, load-run{1,2,3}.json, stress.json, stress-points.json, spike.json, spike-points.json"
echo "load-run*.json 3개의 중앙값을 04-saga-comparison.md와 같은 방식으로 기록하고,"
echo "smoke는 1회 실행 결과를, stress/spike는 summary(집계) + points(시간에 따른 원시"
echo "데이터, VU 변화에 따른 곡선 복원용)를 함께 3.12 벤치마크 리포트에 반영할 것."
