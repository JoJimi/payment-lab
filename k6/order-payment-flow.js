import http from 'k6/http';
import { check } from 'k6';

// 1.20 — 기본 시나리오: 주문 생성 → 결제 요청. 1.21 베이스라인 측정의 기준 스크립트이자,
// 이후 모든 단계(캐싱 전/후, 서킷 유무, 모놀리식 vs MSA)가 비교할 때 재사용한다.
export const options = {
  scenarios: {
    order_to_payment: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '30s',
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);

export default function () {
  const orderRes = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ productId: PRODUCT_ID, quantity: 1 }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(orderRes, {
    '주문 생성 201 또는 409(재고부족)': (r) => r.status === 201 || r.status === 409,
  });

  if (orderRes.status !== 201) {
    return; // 재고 소진 후에는 결제 단계로 넘어가지 않는다 (정상 흐름)
  }

  const order = JSON.parse(orderRes.body);
  // 외부 UUID 라이브러리 없이 VU/반복/시각 조합만으로 충분히 유니크한 키를 만든다.
  const idempotencyKey = `${__VU}-${__ITER}-${Date.now()}`;

  const paymentRes = http.post(
    `${BASE_URL}/api/payments`,
    JSON.stringify({ orderId: order.id, amount: order.totalAmount, currency: order.currency }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey } },
  );

  check(paymentRes, {
    '결제 요청 201': (r) => r.status === 201,
  });
}
