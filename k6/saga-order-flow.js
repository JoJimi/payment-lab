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
// 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — order/payment/inventory/
// notification 4개 서비스 + Kafka + Postgres 3개 + Redis가 전부 로컬에 떠 있어야
// 한다. 로컬에서 scripts/measure-saga-baseline.sh로 실행할 것.

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

export const options = {
  scenarios: {
    order_saga: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    order_success_rate: ['rate>0.99'],
  },
};

const ORDER_SERVICE_URL = __ENV.ORDER_SERVICE_URL || 'http://localhost:8081';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const UNIT_PRICE = __ENV.UNIT_PRICE || '10000';
const CURRENCY = __ENV.CURRENCY || 'KRW';

// Saga 완료(주문 상태가 CREATED에서 벗어남)를 기다리는 최대 시간. 2.15의 기본 타임아웃
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
  let finalStatus = 'CREATED';

  while (Date.now() - sagaStart < POLL_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_MS / 1000);

    const getRes = http.get(`${ORDER_SERVICE_URL}/api/orders/${order.id}`);
    if (getRes.status !== 200) {
      continue; // 일시적 조회 실패는 다음 폴링에서 재시도
    }
    const current = JSON.parse(getRes.body);
    if (current.status !== 'CREATED') {
      finalStatus = current.status;
      break;
    }
  }

  // CodeRabbit 리뷰 — 타임아웃 판정은 루프 안에서 GET 요청 전에 계산해둔 stale한
  // elapsed가 아니라, 응답을 실제로 받은 뒤의 시각으로 다시 계산해야 한다. 마지막
  // 반복이 예산 안에서 시작됐어도 응답 자체가 예산을 넘겨 도착했다면 타임아웃으로
  // 잡아야 한다(부하가 걸렸을 때 Saga가 실제로 느려지는지를 이 지표가 보여줘야 하므로).
  const elapsed = Date.now() - sagaStart;
  const completed = finalStatus !== 'CREATED' && elapsed <= POLL_TIMEOUT_MS;
  // CodeRabbit 리뷰 — 타임아웃/조회 실패로 끝난 주문까지 elapsed(사실상 POLL_TIMEOUT_MS
  // 근처 값)를 saga_completion_duration에 섞으면 p50/p95가 "실제 완료 시간"이 아니라
  // "완료 여부와 무관하게 얼마나 기다렸는가"로 오염된다. 완료가 확인된 주문만 이 Trend에
  // 넣고, 타임아웃 비율은 아래 check()의 실패율로 별도 집계한다.
  if (completed) {
    sagaCompletionDuration.add(elapsed);
  }
  check(null, {
    'Saga가 타임아웃 전에 종결 상태(PAID/FAILED/CANCELLED)로 끝남': () => completed,
  });
}
