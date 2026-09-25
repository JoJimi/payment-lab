import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// 2.20 — 1단계 대비 2단계(서비스 분리 + Kafka Saga) 성능 비교.
//
// 1단계(k6/order-payment-flow.js)는 주문 생성 요청을 보낸 시점부터 결제 응답을 받을
// 때까지, 동기 호출 두 개의 전체 왕복 시간을 측정했다. 2단계는 결제/재고/알림이 전부
// Kafka Saga로 비동기 처리되므로 "주문 생성 API 응답 시간"과 "Saga가 실제로 끝나는
// 시간"이 서로 다른 지표가 됐다 — 이 스크립트는 둘 다 별도 Trend로 측정한다.
// `saga_completion_duration`은 (CodeRabbit 리뷰, PR #82) 주문 API 응답 이후가 아니라
// **주문 요청을 보낸 시점부터** 잰다 — 1단계 지표와 같은 범위(요청 시작~최종 완료)라야
// "API 응답은 빨라졌지만 실제 완료까지는 더 걸릴 수 있다"는 트레이드오프를 공정하게
// 비교할 수 있다(로드맵 2.20 의도, docs/stages/03-service-split-saga.md "다음 단계로
// 넘기는 숙제" 참고).
//
// 3.10 — PROFILE 환경변수로 smoke/load/stress/spike 4개 부하 프로파일을 전환한다.
// 요청 로직(default function)과 측정 지표는 프로파일과 무관하게 동일하다 — 달라지는
// 건 오직 k6 executor 설정(VU 수, 램프업 곡선)과 threshold뿐이다. smoke/load는
// "이 회차가 유효한가"를 확인하는 목적이라 요청 실패를 무관용으로 본다(rate==1).
// stress/spike는 반대로 "얼마나 버티다 무너지는가"를 관찰하는 게 목적이다 — 여기서
// 실패가 나오는 건 버그가 아니라 이 프로파일이 보고 싶어하는 신호 그 자체다. threshold를
// 걸어도 abortOnFail(기본 false)을 켜지 않는 한 실행 중간에 끊기지는 않지만(k6는
// 끝까지 돌고 나서 종료 코드에만 반영한다), 그 "실패로 끝났다"는 최종 상태 자체가
// 오염된다 — 이 회차를 자동화 스크립트가 무효로 취급하거나(예: `set -e`가 걸린
// measure-*.sh 계열) CI에서 실패로 잘못 보고할 수 있다. 그래서 이 두 프로파일은
// threshold를 아예 걸지 않는다 — 수집된 실패율 자체는 order_success_rate 지표(Rate)로
// 여전히 확인할 수 있다.
//
// 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — order/payment/inventory/
// notification 4개 서비스 + Kafka + Postgres 3개 + Redis가 전부 로컬에 떠 있어야
// 한다. 로컬에서 scripts/measure-saga-baseline.sh(load) 또는
// `k6 run --env PROFILE=<smoke|load|stress|spike> k6/saga-order-flow.js`로 실행할 것.

const orderApiDuration = new Trend('order_api_duration', true);
// CodeRabbit 리뷰(PR #82) — sagaStart를 orderStart와 같게 잡아 "주문 요청 시작부터 Saga
// 완료까지"를 측정한다. 1단계 스크립트가 재는 것도 "주문 생성 요청 시작부터 결제 응답까지"
// 전체 왕복 시간이라, 이렇게 맞춰야 두 지표가 같은 범위를 비교하게 된다(주문 API 응답
// 시간만 뺀 "응답 후 대기 시간"으로는 2단계가 부당하게 빨라 보인다).
const sagaCompletionDuration = new Trend('saga_completion_duration', true);
// CodeRabbit 리뷰 — check()만으로는 실패해도 k6 실행 자체는 성공(exit 0)한다. 주문
// 생성이 실패하기 시작해도 measure-saga-baseline.sh가 그 결과를 그대로 저장하고 다음
// 회차로 넘어가버리는 걸 막으려고, 이 비율에 threshold를 걸어 실패율이 높으면 k6
// 자체가 실패하게 한다.
const orderSuccessRate = new Rate('order_success_rate');

// 3.10 — VU 상한은 로컬 1대짜리 개발 머신(Docker Compose로 Kafka/Postgres 3개/Redis를
// 함께 띄운 환경) 기준으로 잡은 기본값이다. 실제로 어디서 무너지는지는 환경마다 다르므로
// 필요하면 STRESS_MAX_VUS/SPIKE_MAX_VUS로 조정할 것.
const STRESS_MAX_VUS = Number(__ENV.STRESS_MAX_VUS || 200);
const SPIKE_BASE_VUS = Number(__ENV.SPIKE_BASE_VUS || 10);
const SPIKE_MAX_VUS = Number(__ENV.SPIKE_MAX_VUS || 300);

const LOAD_PROFILES = {
  // smoke — "이 스크립트/환경이 최소한 정상 작동하는가"만 확인한다. VU 1개로 짧게.
  smoke: {
    executor: 'constant-vus',
    vus: 1,
    duration: '30s',
  },
  // load — 정상 예상 부하를 일정하게 유지. 기존 2.20/3.6 베이스라인 비교와 동일한 형태
  // (VUS/DURATION 환경변수 그대로 유지 — 하위 호환).
  load: {
    executor: 'constant-vus',
    vus: Number(__ENV.VUS || 20),
    duration: __ENV.DURATION || '60s',
  },
  // stress — 정상 용량을 넘어서까지 VU를 계단식으로 올려 어디서 무너지는지 찾는다.
  stress: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '1m', target: Math.round(STRESS_MAX_VUS * 0.25) },
      { duration: '2m', target: Math.round(STRESS_MAX_VUS * 0.5) },
      { duration: '2m', target: STRESS_MAX_VUS },
      { duration: '1m', target: 0 },
    ],
  },
  // spike — 평상시 부하에서 급격히 치솟았다가 다시 가라앉는 트래픽(이벤트성 프로모션 등)을
  // 흉내낸다. 급격한 유입/이탈에 대한 회복력(서킷/Bulkhead가 실제로 방어하는지)이 관심사다.
  spike: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '10s', target: SPIKE_BASE_VUS },
      { duration: '10s', target: SPIKE_MAX_VUS },
      { duration: '30s', target: SPIKE_MAX_VUS },
      { duration: '10s', target: SPIKE_BASE_VUS },
      { duration: '20s', target: 0 },
    ],
  },
};

const PROFILE = __ENV.PROFILE || 'load';
if (!LOAD_PROFILES[PROFILE]) {
  throw new Error(`알 수 없는 PROFILE: "${PROFILE}" — smoke/load/stress/spike 중 하나여야 합니다.`);
}

export const options = {
  scenarios: {
    order_saga: LOAD_PROFILES[PROFILE],
  },
  thresholds: {
    // CodeRabbit 리뷰(PR #82) — rate>0.99는 1% 실패를 허용해버려서 "주문 생성 실패가
    // 있는 회차를 성공으로 처리하지 않기"라는 원래 요구를 완전히 만족하지 못한다.
    // 성능 베이스라인 측정(smoke/load)은 요청 하나라도 실패하면 그 회차 자체가 무효이므로
    // rate==1로 무관용(zero-tolerance)으로 건다. stress/spike는 위 주석 참고 — 걸지 않는다.
    ...(PROFILE === 'smoke' || PROFILE === 'load' ? { order_success_rate: ['rate==1'] } : {}),
  },
};

const ORDER_SERVICE_URL = __ENV.ORDER_SERVICE_URL || 'http://localhost:8081';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const UNIT_PRICE = __ENV.UNIT_PRICE || '10000';
const CURRENCY = __ENV.CURRENCY || 'KRW';

// Saga 완료(sagaStatus가 COMPLETED/FAILED로 종결)를 기다리는 최대 시간. 2.15의 기본 타임아웃
// (10분, app.saga.timeout-minutes)보다 훨씬 짧게 잡는다 — 정상 흐름이면 보통 Outbox
// 릴레이 폴링 주기(기본 1초) x 3홉(order→payment, payment→order/inventory,
// inventory→order/notification) 안에서 끝나야 하고, 이 예산(기본 15초)을 넘기는
// 비율 자체가 "부하 상황에서 Saga가 얼마나 느려지는가"를 보여주는 지표다.
const POLL_TIMEOUT_MS = Number(__ENV.POLL_TIMEOUT_MS || 15000);
const POLL_INTERVAL_MS = Number(__ENV.POLL_INTERVAL_MS || 200);

export default function () {
  const orderStart = Date.now();
  const orderRes = http.post(
    `${ORDER_SERVICE_URL}/api/orders`,
    JSON.stringify({
      productId: PRODUCT_ID,
      quantity: 1,
      unitPrice: UNIT_PRICE,
      currency: CURRENCY,
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  orderApiDuration.add(Date.now() - orderStart);

  const orderCreated = check(orderRes, {
    '주문 생성 201': (r) => r.status === 201,
  });
  orderSuccessRate.add(orderCreated);
  if (orderRes.status !== 201) {
    return; // 검증 실패 등 — 1단계 스크립트의 재고부족(409) 분기와 달리 2단계는
    // 가격/수량을 클라이언트가 보내므로 이 경로는 거의 항상 요청 자체의 문제다.
  }

  const order = JSON.parse(orderRes.body);
  const sagaStart = orderStart;
  // CodeRabbit 리뷰(PR #82) — OrderStatus만으로는 Saga가 진짜 끝났는지 알 수 없다.
  // 정상 흐름에서는 결제만 끝나도 order.status가 PAID가 되지만, 재고 예약/알림 발행은
  // 그 뒤에 따로 진행된다 — order.status를 종결 신호로 쓰면 재고·알림 단계를 측정에서
  // 빼먹은 채 "Saga 완료"로 잘못 집계한다. sagaStatus가 STARTED/COMPENSATING을 벗어나
  // COMPLETED(정상 종료 또는 보상까지 끝난 취소)나 FAILED(보상 자체 실패, 수동 개입
  // 필요)가 될 때까지 기다려야 진짜 종결이다.
  let finalSagaStatus = 'STARTED';

  while (Date.now() - sagaStart < POLL_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_MS / 1000);

    const getRes = http.get(`${ORDER_SERVICE_URL}/api/orders/${order.id}`);
    if (getRes.status !== 200) {
      continue; // 일시적 조회 실패는 다음 폴링에서 재시도
    }
    const current = JSON.parse(getRes.body);
    if (current.sagaStatus === 'COMPLETED' || current.sagaStatus === 'FAILED') {
      finalSagaStatus = current.sagaStatus;
      break;
    }
  }

  // CodeRabbit 리뷰 — 타임아웃 판정은 루프 안에서 GET 요청 전에 계산해둔 stale한
  // elapsed가 아니라, 응답을 실제로 받은 뒤의 시각으로 다시 계산해야 한다. 마지막
  // 반복이 예산 안에서 시작됐어도 응답 자체가 예산을 넘겨 도착했다면 타임아웃으로
  // 잡아야 한다(부하가 걸렸을 때 Saga가 실제로 느려지는지를 이 지표가 보여줘야 하므로).
  const elapsed = Date.now() - sagaStart;
  const completed = finalSagaStatus !== 'STARTED' && finalSagaStatus !== 'COMPENSATING' && elapsed <= POLL_TIMEOUT_MS;
  // CodeRabbit 리뷰 — 타임아웃/조회 실패로 끝난 주문까지 elapsed(사실상 POLL_TIMEOUT_MS
  // 근처 값)를 saga_completion_duration에 섞으면 p50/p95가 "실제 완료 시간"이 아니라
  // "완료 여부와 무관하게 얼마나 기다렸는가"로 오염된다. 완료가 확인된 주문만 이 Trend에
  // 넣고, 타임아웃 비율은 아래 check()의 실패율로 별도 집계한다.
  if (completed) {
    sagaCompletionDuration.add(elapsed);
  }
  check(null, {
    'Saga가 타임아웃 전에 종결 상태(COMPLETED/FAILED)로 끝남': () => completed,
  });
}
