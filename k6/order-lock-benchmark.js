import http from 'k6/http';
import { check } from 'k6';

// 1.13/1.14 — 재고 락 전략별 / 낙관적 락 재시도 횟수별 성능 측정용 k6 시나리오.
// POST /api/orders를 반복 호출해 재고를 소진시킨다. 재고 부족(409)도 "정상 동작"이므로
// 실패로 잡지 않는다 — 실패율은 5xx/네트워크 오류만 센다.
export const options = {
  scenarios: {
    lock_benchmark: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);

export default function () {
  const res = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ productId: PRODUCT_ID, quantity: 1 }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(res, {
    '201(성공) 또는 409(재고부족) 응답': (r) => r.status === 201 || r.status === 409,
  });
}
