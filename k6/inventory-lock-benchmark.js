import http from 'k6/http';
import { check } from 'k6';

// 3.12 — 1.13/1.14 락 전략/재시도 곡선 재측정용. 기존 order-lock-benchmark.js는 2.1
// 서비스 분리 이후 POST /api/orders(order-service)가 즉시 응답하고 재고 차감은 Kafka
// 리스너가 비동기로 처리하게 되면서 더 이상 락 전략의 성능을 격리해서 재지 못한다
// (3.9에서 확인했듯 그 비동기 경로의 병목은 Kafka 리스너 스레드이지 락이 아니다).
// InventoryController(3.12)의 동기 전용 엔드포인트를 직접 두드려 락 전략 자체만 잰다.
export const options = {
  scenarios: {
    lock_benchmark: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
};

const BASE_URL = __ENV.INVENTORY_SERVICE_URL || 'http://localhost:8083';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);

export default function () {
  const res = http.post(`${BASE_URL}/api/inventory/${PRODUCT_ID}/reserve?quantity=1`, null);

  // 재고 부족(409)은 정상 동작이므로 실패로 잡지 않는다. 락 획득 실패(503, INV002 —
  // 낙관적 락 재시도 소진 또는 분산 락 타임아웃)만 이 벤치마크가 재려는 진짜 실패다.
  check(res, {
    '204(성공) 또는 409(재고부족) 응답': (r) => r.status === 204 || r.status === 409,
  });
}
