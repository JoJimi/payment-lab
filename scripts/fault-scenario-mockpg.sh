#!/usr/bin/env bash
set -uo pipefail
# set -e를 쓰지 않는다 — 이 스크립트의 목적 자체가 결제 요청을 실패/타임아웃시키는
# 것이라, curl이 4xx/5xx나 타임아웃을 돌려줘도 스크립트가 죽으면 안 된다.

# 로드맵 3.7 — Mock PG 장애 시나리오 스크립트.
# ResilientMockPgGateway(3.1~3.6, Retry→CircuitBreaker→TimeLimiter→Bulkhead)가 실제로
# 장애 상황에서 어떻게 반응하는지 Grafana에서 눈으로 보기 위해, mock-pg-server의
# POST /pg/_config를 단계적으로 바꿔가며 동시에 결제 요청을 계속 쏜다.
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬에서 돌릴 것(3.8/3.9).
#
# 사용법:
#   docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
#   ./gradlew :mock-pg-server:run &
#   ./gradlew :payment-service:bootRun &
#   ./scripts/fault-scenario-mockpg.sh <시나리오>
#
# 시나리오 (인자 하나, 기본값 all):
#   gradual-latency      점진적 지연 증가 — delayMs를 0 -> 5000ms까지 단계적으로 올린다.
#                         TimeLimiter 상한(application.yml의 timeout-duration, 기본 3s)을
#                         넘는 순간부터 타임아웃이 실패로 잡히기 시작해 서킷이 CLOSED에서
#                         OPEN으로 넘어가는 지점을 관찰할 수 있다.
#   intermittent-failure 간헐적 실패 — failureRate를 0 -> 0.8까지 올렸다가 0으로 되돌린다.
#                         실패율이 failure-rate-threshold(기본 50%)를 넘으면 OPEN, 이후
#                         wait-duration-in-open-state(기본 10s) 뒤 HALF_OPEN으로 전이해
#                         permitted-number-of-calls-in-half-open-state(기본 3)건을
#                         시험적으로 통과시키는 과정을 관찰할 수 있다.
#   complete-down         완전 다운 — forceTimeout=true로 Mock PG가 응답 자체를 하지 않는
#                         상태를 재현한다(TimeLimiter가 3s에서 먼저 포기). 3.9(방어 로직
#                         없는 버전과의 비교)의 "5초 지연" 재현에도 이 옵션을 쓸 수 있다.
#   slow-recovery         느린 복구 — complete-down 이후 delayMs를 5000 -> 0까지 서서히
#                         낮춰가며 서킷이 HALF_OPEN에서 바로 CLOSED로 안정되는지, 아니면
#                         아직 느린 응답 때문에 다시 OPEN으로 튕기는(flapping) 구간이
#                         있는지 관찰할 수 있다.
#   all                   위 네 가지를 순서대로, 사이에 정상 구간을 끼워 전부 실행한다
#                         (기본값 — Grafana에서 네 시나리오를 시간순으로 나란히 비교하기
#                         좋다).
#
# 관찰 방법: Grafana(http://localhost:3000)에서 아래 두 가지를 확인한다.
#   1. resilience4j.circuitbreaker.instances.mockPg의 상태 전이(CLOSED/OPEN/HALF_OPEN) —
#      정확한 Prometheus 메트릭 이름은 로컬에서 실행하며 다음으로 직접 확인할 것
#      (resilience4j-spring-boot4가 자동 등록하므로 커스텀 메트릭 코드는 필요 없다):
#        curl -s http://localhost:8082/actuator/prometheus | grep circuitbreaker
#      보통 resilience4j_circuitbreaker_state(게이지, state 태그별 1/0)와
#      resilience4j_circuitbreaker_calls_seconds_count(kind 태그: successful/failed/
#      not_permitted 등) 형태로 나온다 — payment-lab-overview.json에 새 패널로 추가할 것.
#   2. 이 스크립트가 표준출력에 찍는 "=== ... ===" 구간 타임스탬프와 Grafana 그래프의
#      시간축을 맞춰보면 "어느 설정 변경이 어느 상태 전이를 일으켰는지" 대응시킬 수 있다.
#
# 3.9(방어 로직 없는 버전과 비교)는 이 스크립트만으로는 못한다 — ResilientMockPgGateway가
# 처음 배선되기 전 커밋(3.1 이전, PR #85 병합 전 상태)으로 체크아웃하거나
# PaymentService가 MockPgClient를 직접 호출하도록 임시로 바꾼 뒤, complete-down
# 시나리오와 동시에 payment-service의 HTTP 스레드풀/DB 커넥션 풀 지표(Tomcat
# threads-busy, HikariCP 커넥션 대기)가 고갈되는지 별도로 관찰해야 한다.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_wait-for-app.sh
source "${SCRIPT_DIR}/_wait-for-app.sh"

MOCKPG_URL=${MOCKPG_URL:-http://localhost:8090}
PAYMENT_SERVICE_URL=${PAYMENT_SERVICE_URL:-http://localhost:8082}
CURRENCY=${CURRENCY:-KRW}
UNIT_PRICE=${UNIT_PRICE:-10000}
# 결제 요청 사이 간격(초). 너무 촘촘하면 Bulkhead(3.5, core=4/max=8/queue=8)가 금방 차서
# 관찰하려는 CircuitBreaker 전이보다 BulkheadFullException이 먼저 보일 수 있다.
REQUEST_INTERVAL_SEC=${REQUEST_INTERVAL_SEC:-0.3}

# 스크립트 실행 전체에서 유일한 orderId를 보장한다(payments 테이블의
# ux_payments_active_order 부분 유니크 인덱스 — 같은 orderId로 PENDING/APPROVED/UNKNOWN
# 결제가 중복되면 INSERT가 막힌다).
ORDER_ID_BASE=$(($(date +%s) * 1000))
REQUEST_COUNTER=0

mockpg_config() {
  local delay_ms=$1 failure_rate=$2 forced_error=$3 force_timeout=$4
  curl -sf -X POST "${MOCKPG_URL}/pg/_config" \
    -H 'Content-Type: application/json' \
    -d "{\"delayMs\":${delay_ms},\"failureRate\":${failure_rate},\"forcedErrorCode\":${forced_error},\"forceTimeout\":${force_timeout}}" \
    >/dev/null
}

mockpg_reset() {
  curl -sf -X POST "${MOCKPG_URL}/pg/_config" -H 'Content-Type: application/json' -d '{"reset":true}' >/dev/null
}

# 결제 한 건을 보내고 (HTTP 상태, 걸린 시간)만 한 줄로 남긴다 — 실패/타임아웃이
# 이 스크립트에서는 정상적으로 기대되는 결과이므로 에러로 취급하지 않는다.
send_payment() {
  REQUEST_COUNTER=$((REQUEST_COUNTER + 1))
  local order_id=$((ORDER_ID_BASE + REQUEST_COUNTER))
  local idempotency_key="fault-scenario-${order_id}"
  local result
  result=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 15 \
    -X POST "${PAYMENT_SERVICE_URL}/api/payments" \
    -H "Idempotency-Key: ${idempotency_key}" \
    -H 'Content-Type: application/json' \
    -d "{\"orderId\":${order_id},\"amount\":${UNIT_PRICE},\"currency\":\"${CURRENCY}\"}" \
    2>/dev/null || echo "curl-error -1")
  printf '  orderId=%s -> %s\n' "${order_id}" "${result}"
}

# duration_sec 동안 REQUEST_INTERVAL_SEC 간격으로 결제 요청을 계속 보낸다.
generate_load() {
  local duration_sec=$1
  local end_time=$((SECONDS + duration_sec))
  while [ "${SECONDS}" -lt "${end_time}" ]; do
    send_payment
    sleep "${REQUEST_INTERVAL_SEC}"
  done
}

phase() {
  local title=$1
  echo ""
  echo "=== [$(date '+%H:%M:%S')] ${title} ==="
}

scenario_gradual_latency() {
  phase "3.7 gradual-latency: 시작 (delayMs 0 -> 5000, 단계별 15초)"
  local steps=(0 500 1000 2000 3000 4000 5000)
  for delay in "${steps[@]}"; do
    phase "delayMs=${delay}"
    mockpg_config "${delay}" 0.0 null false
    generate_load 15
  done
  phase "gradual-latency: 정상 구간으로 복귀"
  mockpg_reset
  generate_load 15
}

scenario_intermittent_failure() {
  phase "3.7 intermittent-failure: 시작 (failureRate 0 -> 0.8, 단계별 15초)"
  local steps=(0.0 0.2 0.4 0.6 0.8)
  for rate in "${steps[@]}"; do
    phase "failureRate=${rate}"
    mockpg_config 0 "${rate}" null false
    generate_load 15
  done
  phase "intermittent-failure: 정상 구간으로 복귀 (HALF_OPEN -> CLOSED 관찰)"
  mockpg_reset
  generate_load 20
}

scenario_complete_down() {
  phase "3.7 complete-down: 시작 (forceTimeout=true, 30초)"
  mockpg_config 0 0.0 null true
  generate_load 30
  phase "complete-down: 정상 구간으로 복귀"
  mockpg_reset
  generate_load 15
}

scenario_slow_recovery() {
  phase "3.7 slow-recovery: 완전 다운으로 시작 (30초)"
  mockpg_config 0 0.0 null true
  generate_load 30
  phase "slow-recovery: delayMs 5000 -> 0으로 서서히 복구 (단계별 15초)"
  local steps=(5000 3000 1500 500 0)
  for delay in "${steps[@]}"; do
    phase "delayMs=${delay}"
    mockpg_config "${delay}" 0.0 null false
    generate_load 15
  done
}

SCENARIO=${1:-all}

phase "Mock PG(${MOCKPG_URL})/payment-service(${PAYMENT_SERVICE_URL}) readiness 확인"
wait_for_app_ready "${PAYMENT_SERVICE_URL}/actuator/health"
mockpg_reset

case "${SCENARIO}" in
  gradual-latency) scenario_gradual_latency ;;
  intermittent-failure) scenario_intermittent_failure ;;
  complete-down) scenario_complete_down ;;
  slow-recovery) scenario_slow_recovery ;;
  all)
    scenario_gradual_latency
    scenario_intermittent_failure
    scenario_complete_down
    scenario_slow_recovery
    ;;
  *)
    echo "알 수 없는 시나리오: ${SCENARIO} (gradual-latency|intermittent-failure|complete-down|slow-recovery|all)" >&2
    exit 1
    ;;
esac

mockpg_reset
phase "완료 — Mock PG 설정을 기본값으로 되돌렸습니다"
