#!/usr/bin/env bash
set -euo pipefail

# 로드맵 1.13 — 재고 락 4종(NONE/PESSIMISTIC/OPTIMISTIC/DISTRIBUTED) 성능 비교.
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬 Docker 환경에서 돌릴 것.
# 부록 C: "벤치마크 숫자가 노이즈" → CI 러너에서 절대값을 측정하지 말 것.
#
# 사용법:
#   docker compose -f docker-compose.yml up -d
#   ./gradlew mockPgRun &
#   ./scripts/benchmark-lock-strategies.sh
#
# 실행 후 benchmarks/raw/<전략>.json의 k6 summary를 읽어
# benchmarks/01-lock-strategies.md 표를 채운다 (워밍업 후 3회 반복, 중앙값 — CLAUDE.md).

STRATEGIES=(NONE PESSIMISTIC OPTIMISTIC DISTRIBUTED)
PRODUCT_ID=${PRODUCT_ID:-1}
STOCK=${STOCK:-100}
VUS=${VUS:-50}
DURATION=${DURATION:-30s}

mkdir -p benchmarks/raw

for STRATEGY in "${STRATEGIES[@]}"; do
  echo "=== ${STRATEGY} ==="

  docker exec payment-lab-postgres psql -U cs -d payment_lab_dev -c \
    "UPDATE inventory SET available = ${STOCK}, reserved = 0 WHERE product_id = ${PRODUCT_ID};"

  # 전략 전환은 재기동으로만 한다 (CLAUDE.md: 한 번에 한 개념만 켠다 — 핫스위치 없음).
  pkill -f 'org.example.cs_study.CsStudyApplication' 2>/dev/null || true
  sleep 2
  ./gradlew bootRun --args="--inventory.lock-strategy=${STRATEGY}" &
  APP_PID=$!
  sleep 15 # 기동 대기 (Boot 4 기준선 17~22초, 5.9 참고)

  k6 run \
    --env VUS="${VUS}" --env DURATION="${DURATION}" --env PRODUCT_ID="${PRODUCT_ID}" \
    --summary-export="benchmarks/raw/${STRATEGY}.json" \
    k6/order-lock-benchmark.js

  kill "${APP_PID}" 2>/dev/null || true
  sleep 2
done

echo "완료. benchmarks/raw/*.json을 읽어 benchmarks/01-lock-strategies.md 표를 채우세요."
