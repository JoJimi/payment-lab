#!/usr/bin/env bash
set -uo pipefail
# set -e를 쓰지 않는다 — 이 스크립트의 목적 자체가 주문/결제를 실패/타임아웃시키는
# 것이라, curl이 4xx/5xx나 타임아웃을 돌려줘도 스크립트가 죽으면 안 된다. 다만 Mock PG
# 설정 변경(mockpg_config/mockpg_reset)은 실패하면 시나리오 자체가 무의미해지므로 그
# 요청만은 실패 시 즉시 종료한다(CodeRabbit 리뷰, PR #91).

# 로드맵 3.7 — Mock PG 장애 시나리오 스크립트.
# ResilientMockPgGateway(3.1~3.6, Retry→CircuitBreaker→TimeLimiter→Bulkhead)가 실제로
# 장애 상황에서 어떻게 반응하는지 Grafana에서 눈으로 보기 위해, mock-pg-server의
# POST /pg/_config를 단계적으로 바꿔가며 동시에 주문을 계속 생성한다.
#
# 왜 payment-service를 직접 부르지 않고 order-service를 거치는가(CodeRabbit 리뷰, PR
# #91) — POST /api/payments(PaymentService.requestPayment)는 OrderValidator.assertValid를
# 먼저 호출하는데, 2.1/2.3 이후 유일한 구현체인 UnimplementedOrderValidator는 모든
# 요청을 NOT_IMPLEMENTED(501)로 거부한다(order-service와의 실제 연동은 Kafka Saga로만
# 복원됨). 즉 payment-service를 직접 두드리면 Mock PG에 아예 도달하지 못한다 — 실제로
# ResilientMockPgGateway를 거치는 유일한 경로는 order-service가 주문을 만들며 발행하는
# payment.requested 이벤트를 PaymentRequestedListener가 받아 호출하는
# PaymentService.requestPaymentFromSaga뿐이다. 그래서 이 스크립트는 order-service의
# POST /api/orders로 주문을 계속 생성해 그 경로를 통해 부하를 만든다.
#
# 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — 로컬에서 돌릴 것(3.8/3.9).
#
# 사용법:
#   docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
#   ./gradlew :mock-pg-server:run &
#   ./gradlew :order-service:bootRun &
#   ./gradlew :payment-service:bootRun &
#   ./gradlew :inventory-service:bootRun &
#   ./gradlew :notification-service:bootRun &
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
ORDER_SERVICE_URL=${ORDER_SERVICE_URL:-http://localhost:8081}
PAYMENT_SERVICE_URL=${PAYMENT_SERVICE_URL:-http://localhost:8082}
PRODUCT_ID=${PRODUCT_ID:-1}
STOCK=${STOCK:-100000} # 부하 자체가 목적이라 재고 소진으로 주문이 막히지 않게 넉넉히 채운다.
UNIT_PRICE=${UNIT_PRICE:-10000}
CURRENCY=${CURRENCY:-KRW}
# 요청 사이 간격(초). 너무 촘촘하면 Bulkhead(3.5, core=4/max=8/queue=8)가 금방 차서
# 관찰하려는 CircuitBreaker 전이보다 BulkheadFullException이 먼저 보일 수 있다.
REQUEST_INTERVAL_SEC=${REQUEST_INTERVAL_SEC:-0.3}

# Mock PG 설정 변경/초기화 — 실패하면 시나리오 자체가 의도한 상태와 어긋나므로
# 즉시 종료한다(CodeRabbit 리뷰, PR #91). 결제/주문 요청의 실패는 이 스크립트가
# 의도적으로 유발하는 정상적인 결과라 별도로 취급한다(아래 send_order 참고).
mockpg_config() {
  local delay_ms=$1 failure_rate=$2 forced_error=$3 force_timeout=$4
  if ! curl -sf --connect-timeout 5 --max-time 15 -X POST "${MOCKPG_URL}/pg/_config" \
    -H 'Content-Type: application/json' \
    -d "{\"delayMs\":${delay_ms},\"failureRate\":${failure_rate},\"forcedErrorCode\":${forced_error},\"forceTimeout\":${force_timeout}}" \
    >/dev/null; then
    echo "Mock PG 설정 요청에 실패했습니다 (${MOCKPG_URL}/pg/_config) — 시나리오를 중단합니다." >&2
    exit 1
  fi
}

mockpg_reset() {
  if ! curl -sf --connect-timeout 5 --max-time 15 -X POST "${MOCKPG_URL}/pg/_config" \
    -H 'Content-Type: application/json' -d '{"reset":true}' >/dev/null; then
    echo "Mock PG 초기화 요청에 실패했습니다 (${MOCKPG_URL}/pg/_config)." >&2
    return 1
  fi
}

# 스크립트가 정상 종료하든, 중간에 중단(Ctrl-C)되든 로컬 Mock PG를 장애 상태로
# 남겨두지 않는다(CodeRabbit 리뷰, PR #91) — 이후 다른 작업(3.8/3.9 관찰, 다른
# 시나리오 재실행)이 이전 실행의 잔여 설정에 영향받지 않게 한다.
cleanup() {
  echo ""
  echo "정리 중 — Mock PG 설정을 기본값으로 되돌립니다"
  mockpg_reset || echo "정리 중 Mock PG 초기화에 실패했습니다 — 다음 실행 전에 수동으로 확인하세요." >&2
}
trap cleanup EXIT INT TERM

# order-service에 주문 하나를 생성한다(성공/실패 모두 이 스크립트 입장에서는 정상
# 결과다 — 실패/타임아웃을 관찰하는 게 목적이므로 curl 실패 자체를 에러로 취급하지
# 않는다). 실제 결제 처리는 order-service가 발행한 payment.requested 이벤트를
# PaymentRequestedListener가 받아 비동기로 수행한다(위 헤더 주석 참고) — 이 함수는
# Saga 완료를 기다리지 않는다.
send_order() {
  local result
  result=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 15 \
    -X POST "${ORDER_SERVICE_URL}/api/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"productId\":${PRODUCT_ID},\"quantity\":1,\"unitPrice\":${UNIT_PRICE},\"currency\":\"${CURRENCY}\"}" \
    2>/dev/null || echo "curl-error -1")
  printf '  order -> %s\n' "${result}"
}

# duration_sec 동안 REQUEST_INTERVAL_SEC 간격으로 주문 생성 요청을 계속 보낸다.
generate_load() {
  local duration_sec=$1
  local end_time=$((SECONDS + duration_sec))
  while [ "${SECONDS}" -lt "${end_time}" ]; do
    send_order
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

phase "order-service(${ORDER_SERVICE_URL})/payment-service(${PAYMENT_SERVICE_URL}) readiness 확인"
wait_for_app_ready "${ORDER_SERVICE_URL}/actuator/health"
wait_for_app_ready "${PAYMENT_SERVICE_URL}/actuator/health"

if ! mockpg_reset; then
  echo "Mock PG(${MOCKPG_URL})에 연결할 수 없습니다 — ./gradlew :mock-pg-server:run으로 먼저 띄우세요." >&2
  exit 1
fi

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

phase "완료"
