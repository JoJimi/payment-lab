#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_wait-for-app.sh
source "${SCRIPT_DIR}/_wait-for-app.sh"

# 로드맵 2.20 — 1단계 대비 2단계(서비스 분리 + Kafka Saga) 성능 비교.
# 시나리오는 k6/saga-order-flow.js (주문 생성 → Saga 완료까지 폴링).
# 1단계 기준값은 benchmarks/03-baseline.md, 결과는 benchmarks/04-saga-comparison.md에
# 직접 기록할 것(1.21과 같은 형식 — 워밍업 1회 + 측정 3회, 중앙값).
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
#   ./scripts/measure-saga-baseline.sh
#
# 1단계 measure-baseline.sh와 다른 점: 대상이 order-service(8081) 하나이고, 결제/재고/
# 알림은 전부 이 서비스가 발행한 이벤트를 다른 서비스가 비동기로 처리한다 — k6 스크립트가
# "주문 생성 API 응답 시간"과 "Saga가 실제로 끝나는 시간"을 별도로 측정하는 이유다.

PRODUCT_ID=${PRODUCT_ID:-1}
STOCK=${STOCK:-100000} # 1.21과 동일하게 재고 소진 자체가 목적이 아니므로 넉넉하게
UNIT_PRICE=${UNIT_PRICE:-10000}
CURRENCY=${CURRENCY:-KRW}
VUS=${VUS:-20}
DURATION=${DURATION:-60s}
ORDER_SERVICE_URL=${ORDER_SERVICE_URL:-http://localhost:8081}
INVENTORY_SERVICE_URL=${INVENTORY_SERVICE_URL:-http://localhost:8083}

mkdir -p benchmarks/raw

# CodeRabbit 리뷰(PR #82) — 새 DB에서 inventory-service:bootRun 직후 바로 이 UPDATE를
# 실행하면 Flyway 마이그레이션(inventory 테이블 생성)보다 먼저 실행될 수 있다. Spring
# Boot는 Flyway 마이그레이션이 끝나야 컨텍스트가 뜨고 actuator/health가 UP이 되므로,
# inventory-service의 readiness를 먼저 확인하면 테이블 존재가 보장된다.
wait_for_app_ready "${INVENTORY_SERVICE_URL}/actuator/health"

# inventory 테이블은 이제 postgres-inventory(2.2, 5434 포트) 안에 있다 — 1단계의 단일
# payment-lab-postgres 컨테이너가 아니다.
#
# CodeRabbit 리뷰(PR #82) — 마이그레이션(V1~V5)은 products/inventory 테이블만 만들 뿐
# PRODUCT_ID=1 행을 시드하지 않는다. 신선한 DB에서 그냥 UPDATE만 실행하면 0건 갱신된 채
# 조용히 다음 단계로 넘어가고, 이후 k6가 존재하지 않는 재고를 예약하려다 실패하면서
# 측정치 전체가 무효가 된다. RETURNING으로 실제 갱신 행을 확인해 없으면 즉시 종료한다.
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

# CodeRabbit 리뷰(PR #82) — bootRun 직후 바로 워밍업을 쏘면 order-service가 아직 안 떠서
# 연결 실패가 난다. saga-order-flow.js의 order_success_rate threshold가 rate==1(무관용)로
# 강화된 뒤로는 이 실패 하나로도 워밍업 자체가 실패 종료해 측정 3회가 아예 안 돈다.
wait_for_app_ready "${ORDER_SERVICE_URL}/actuator/health"

echo "=== 워밍업 ==="
k6 run \
  --env VUS="${VUS}" --env DURATION=10s --env PRODUCT_ID="${PRODUCT_ID}" \
  --env UNIT_PRICE="${UNIT_PRICE}" --env CURRENCY="${CURRENCY}" \
  --env ORDER_SERVICE_URL="${ORDER_SERVICE_URL}" \
  k6/saga-order-flow.js >/dev/null

for i in 1 2 3; do
  echo "=== 측정 ${i}/3 ==="
  k6 run \
    --env VUS="${VUS}" --env DURATION="${DURATION}" --env PRODUCT_ID="${PRODUCT_ID}" \
    --env UNIT_PRICE="${UNIT_PRICE}" --env CURRENCY="${CURRENCY}" \
    --env ORDER_SERVICE_URL="${ORDER_SERVICE_URL}" \
    --summary-export="benchmarks/raw/saga-run${i}.json" \
    k6/saga-order-flow.js
done

echo "완료. benchmarks/raw/saga-run*.json 3개의 중앙값을 benchmarks/04-saga-comparison.md에"
echo "기록하고, benchmarks/03-baseline.md(1단계)와 나란히 비교할 것 — 특히:"
echo "  - order_api_duration (2단계 주문 생성 API 응답) vs 1단계 전체 왕복 시간"
echo "  - saga_completion_duration (2단계 Saga 실제 완료까지) vs 1단계 전체 왕복 시간"
echo "  - 'Saga가 타임아웃 전에 종결 상태로 끝남' check 실패율 — 부하 상황에서 Saga가"
echo "    POLL_TIMEOUT_MS(기본 15초) 안에 못 끝나는 비율"
