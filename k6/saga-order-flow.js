import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// 2.20 — 1단계 대비 2단계(서비스 분리 + Kafka Saga) 성능 비교.
//
// 1단계(k6/order-payment-flow.js)는 주문 생성 → 결제 요청, 두 동기 API 호출의 왕복
// 시간을 측정했다. 2단계는 결제/재고/알림이 전부 Kafka Saga로 비동기 처리되므로
// "주문 생성 API 응답 시간"과 "Saga가 실제로 끝나는 시간"이 서로 다른 지표가 됐다 —
// 이 스크립트는 둘 다 별도 Trend로 측정해서 "API 응답은 빨라졌지만 실제 완료까지는
// 더 걸릴 수 있다"는 트레이드오프를 숫자로 남긴다(로드맵 2.20 의도, docs/stages/
// 03-service-split-saga.md "다음 단계로 넘기는 숙제" 참고).
//
// 이 세션(Docker 없는 원격 컨테이너)에서는 실행할 수 없다 — order/payment/inventory/
// notification 4개 서비스 + Kafka + Postgres 3개 + Redis가 전부 로컬에 떠 있어야
// 한다. 로컬에서 scripts/measure-saga-baseline.sh로 실행할 것.

const orderApiDuration = new Trend('order_api_duration', true);
const sagaCompletionDuration = new Trend('saga_completion_duration', true);

export const options = {
  scenarios: {
    order_saga: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '60s',
    },
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

  check(orderRes, {
    '주문 생성 201': (r) => r.status === 201,
  });
  if (orderRes.status !== 201) {
    return; // 검증 실패 등 — 1단계 스크립트의 재고부족(409) 분기와 달리 2단계는
    // 가격/수량을 클라이언트가 보내므로 이 경로는 거의 항상 요청 자체의 문제다.
  }

  const order = JSON.parse(orderRes.body);
  const sagaStart = Date.now();
  let finalStatus = 'CREATED';
  let elapsed = 0;

  while (elapsed < POLL_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_MS / 1000);
    elapsed = Date.now() - sagaStart;

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

  sagaCompletionDuration.add(Date.now() - sagaStart);
  check(null, {
    'Saga가 타임아웃 전에 종결 상태(PAID/FAILED/CANCELLED)로 끝남': () => finalStatus !== 'CREATED',
  });
}
