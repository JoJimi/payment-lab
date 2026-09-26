import http from 'k6/http';
import { check } from 'k6';

// 3.12 — 캐시 유무 성능 비교. MODE=uncached는 ProductService.getProductUnprotected(1.17
// 대조군, 매 요청 DB 조회 — /api/products/{id}/uncached), MODE=cached(기본값)는
// getProduct(1.15, sync=true 캐시 — /api/products/{id})를 HTTP로 두드린다.
export const options = {
  scenarios: {
    cache_benchmark: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '30s',
    },
  },
};

const BASE_URL = __ENV.INVENTORY_SERVICE_URL || 'http://localhost:8083';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const PATH_SUFFIX = __ENV.MODE === 'uncached' ? '/uncached' : '';

export default function () {
  const res = http.get(`${BASE_URL}/api/products/${PRODUCT_ID}${PATH_SUFFIX}`);
  check(res, { '200 응답': (r) => r.status === 200 });
}
