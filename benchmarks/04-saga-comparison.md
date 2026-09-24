# 2.20 — 1단계 대비 2단계 성능 비교

로드맵 2.20. 시나리오는 `k6/saga-order-flow.js`(주문 생성 → Saga 완료까지 폴링),
실행은 `scripts/measure-saga-baseline.sh`. 로컬(Windows, Git Bash + Docker Desktop)에서
2026-09-24 실측했다. 측정 과정에서 겪은 문제(재고 시드 데이터 부재, 로컬 리소스 경합으로
VUs=20 전체 실패 등)와 그 대응은 [troubleshooting/06-saga-performance-measurement.md]
(../docs/troubleshooting/06-saga-performance-measurement.md)에 별도로 기록했다.

## 측정 환경

| 항목 | 값 |
|---|---|
| 대상 | order-service(`:8081`) — payment/inventory/notification-service는 Kafka 이벤트로 간접 참여 |
| DLQ 재시도 | 500ms 간격, 최초 시도 포함 총 3회 (`common-kafka`, 2.16) |
| Saga 타임아웃 | 기본 10분(`app.saga.timeout-minutes`), 폴링 주기 30초 |
| **VUs (공식 측정값)** | **5** — 20이 아니다. 아래 "VUs별 부하 한계 탐색" 참고 — 로컬 머신(서비스 4개 JVM + mock-pg-server + Kafka + Postgres 3개 + Redis 동시 구동)에서 VUs=10부터 이미 Saga 타임아웃 실패가 나기 시작해, 실패율 0%가 보장되는 최대값인 5로 확정했다 |
| Duration | 60s |
| 반복 | 워밍업 1회 + 측정 3회, 중앙값 |
| 대상 상품 | 단일 상품(`PRODUCT_ID=1`, 재고 100000) — 1.21과 동일한 한계(단일 핫로우 경합)가 그대로 적용됨 |
| `POLL_TIMEOUT_MS` | 15000 (기본값 — Saga 완료를 기다리는 최대 시간, 이 안에 안 끝나면 미완료로 집계) |
| 상품 시드 | 마이그레이션에 시드 데이터가 없어 `products`/`inventory`에 `product_id=1` 행을 수동으로 INSERT한 뒤 측정(06번 문서 참고) |

## 실행 방법

```bash
docker compose -f docker-compose.yml up -d
cp .env.example .env  # 최초 1회, 값 채우기
set -a && source ./.env && set +a
./gradlew mock-pg-server:run &
./gradlew order-service:bootRun &
./gradlew payment-service:bootRun &
./gradlew inventory-service:bootRun &
./gradlew notification-service:bootRun &
./scripts/measure-saga-baseline.sh
```

## 결과 — 공식 측정 (VUs=5)

3회 측정 원본:

| 회차 | order_api p50 | order_api p95 | saga_completion p50 | saga_completion p95 | Saga 타임아웃 내 종결 실패율 | 실측 주문 생성률(iterations/s) |
|---|---|---|---|---|---|---|
| 1 | 272ms | 850.14ms | 4.97s | 9.70s | 0% | 0.9426/s |
| 2 | 186ms | 863.74ms | 4.93s | 7.57s | 0% | 0.9757/s |
| 3 | 130.5ms | 320.25ms | 3.31s | 7.23s | 0% | 1.3347/s |

중앙값(지표별):

| 지표 | 값 |
|---|---|
| order_api_duration p50 | 186ms |
| order_api_duration p95 | 850.14ms |
| saga_completion_duration p50 | 4.93s |
| saga_completion_duration p95 | 7.57s |
| Saga 타임아웃 내 종결 실패율 | 0% |
| 실측 주문 생성률(iterations/s) | 0.9757/s |

## VUs별 부하 한계 탐색

정식 측정을 VUs=20이 아니라 5로 확정한 근거. 셋 다 동일 조건(`DURATION=60s`,
`POLL_TIMEOUT_MS=15000` 기본값)으로 측정했다 — VUs=20만 추가로 워밍업 10s 포함.

| VUs | Saga 타임아웃 내 종결 실패율 (3회 중앙값) | order_api p50 | order_api p95 | 실측 주문 생성률 |
|---|---|---|---|---|
| 5 | **0%** | 186ms | 850ms | 0.976/s |
| 10 | 57% | 2.37s | 6.81s | 0.665/s |
| 20 | **100%** | 5.26s | 15.98s | 1.066/s |

```
Saga 타임아웃 실패율
VUs=5   ░░░░░░░░░░   0%
VUs=10  ██████░░░░   57%
VUs=20  ██████████   100%
```

VUs=5→10 사이에서 급격히 무너진다 — 이 로컬 머신(서비스 4개 JVM + mock-pg-server + Kafka
+ Postgres 3개 + Redis + k6를 한 데스크톱에서 동시 구동)이 감당하는 한계이지, 2단계
아키텍처 자체의 처리량 한계가 아니다. `order_api_duration`(동기 API 하나 응답)조차
VUs=5의 ~186ms에서 VUs=20의 ~5.26s로 튀는 걸 보면, 애초에 CPU 경합이 원인이라는 근거가
된다(자세한 진단 과정은 `docs/troubleshooting/06-saga-performance-measurement.md` 참고).

**참고(진단용, 정식 측정 아님)** — VUs=20에 `POLL_TIMEOUT_MS=60000`을 줘서 "부하 상황에서
결국 얼마나 걸려서 끝나는가"만 확인한 결과(`DURATION=10s`, 표본 22~25건/회차로 정식
측정보다 적음): `saga_completion_duration` 중앙값 18.5~32s, p95 최대 37s. VUs=5 대비
6~10배 느려진다 — 안 끝나는 게 아니라, 이 머신에서 VUs=20을 감당 못 해 심하게 밀리는 것.

## 1단계 대비 비교

**주의 — VUs가 다르다(1단계 20 vs 2단계 5).** 로컬 리소스 제약 때문에 2단계는 5로 낮춰
측정했다("VUs별 부하 한계 탐색" 참고) — 아래 비교는 "같은 부하에서의 비교"가 아니라
**서로 다른 부하 수준에서 각자 안정적으로 측정 가능했던 값**이라는 점을 감안해서 볼 것.

| 지표 | 1단계 (`03-baseline.md`, VUs=20) | 2단계 order_api (VUs=5) | 2단계 saga_completion (VUs=5) |
|---|---|---|---|
| p50 | 1.63s | 0.19s | 4.93s |
| p95 | 5.54s | 0.85s | 7.57s |
| TPS / 실측 주문 생성률 | 9.23/s | — | 0.976/s |

**주의 — 1단계 p50/p95는 같은 범위의 지표가 아니다**(CodeRabbit 리뷰, PR #82).
`03-baseline.md`의 1.63s/5.54s는 `k6/order-payment-flow.js`가 별도 Trend 없이 남긴 k6
기본 `http_req_duration` 요약값이다 — 이건 "주문 요청 시작~결제 응답 완료"라는 반복(iteration)
단위 소요 시간이 아니라, **주문 생성 요청과 결제 요청 두 종류의 개별 HTTP 요청 시간을 하나의
분포로 섞은 값**이다. 반면 2단계 `saga_completion_duration`은 반복 전체(주문 요청 시작부터
Saga 종결까지)를 재는 커스텀 Trend다. 그래서 이 표의 p50/p95 비교는 "정확히 같은 범위의
숫자"가 아니라 참고용 근사치로만 볼 것 — 엄밀히 비교하려면 1단계 스크립트에도 주문
요청부터 결제 응답까지를 묶는 Trend를 추가해야 하는데, 그건 이미 완료된 1단계 산출물을
건드리는 일이라 범위 밖으로 남겨둔다.

**결과 요약**: `order_api_duration`(p50 0.19s)은 1단계 전체 왕복(p50 1.63s)보다 훨씬
빠르다 — 예상대로 주문 생성이 더 이상 결제 승인을 동기로 기다리지 않기 때문이다.
반대로 `saga_completion_duration`(p50 4.93s)은 1단계보다 3배 가까이 느리다 — Outbox
릴레이 폴링 주기 × 3홉(order→payment, payment→order/inventory, inventory→
order/notification)이 누적된 결과로, 로드맵 2.20이 예고한 "응답은 빨라지지만 완료는
느려진다"는 트레이드오프가 실측으로 확인됐다. 다만 VUs가 다르다는 위 주의사항 때문에,
이 차이의 얼마만큼이 아키텍처 트레이드오프이고 얼마만큼이 부하 차이 때문인지는 완전히
분리되지 않는다 — 동일 하드웨어에서 VUs를 맞춰 재측정하는 것이 이상적이나 이번 측정
환경(개발자 로컬 데스크톱)에서는 불가능했다.

## 참고

- **`saga_completion_duration`은 `OrderStatus`가 아니라 `sagaStatus`(COMPLETED/FAILED)로
  종결을 판정한다**(CodeRabbit 리뷰, PR #82). 정상 흐름에서는 결제만 끝나도 주문 상태가
  `PAID`가 되지만, 재고 예약·알림 발행은 그 뒤에 별도로 끝난다 — `OrderStatus`만 보면
  이 단계들을 측정에서 빼먹은 채 "Saga 완료"로 잘못 집계하게 된다. `GET /api/orders/{id}`
  응답에 `sagaStatus` 필드를 추가해(`OrderResponse`, `docs/api/openapi.yaml`) 이 문제를
  해결했다.
- **VU 수가 같다고 부하(주문 유입률)가 같은 건 아니다**(CodeRabbit 리뷰, PR #82).
  `constant-vus`는 닫힌 루프(closed workload) 모델이라, 각 VU가 "이전 반복(주문 생성 +
  Saga 완료 폴링)이 끝나야 다음 반복을 시작한다." 2단계는 폴링 대기 때문에 반복 1회가
  1단계보다 오래 걸릴 가능성이 높고, 그러면 같은 VUs=20이어도 실제 초당 주문 생성
  건수(`iterations.rate`)는 1단계보다 **적어진다** — 즉 2단계 쪽이 더 가벼운 부하에서
  측정된 걸 수 있다. 그래서 위 결과표에 회차별 `iterations.rate`를 반드시 같이 기록하고,
  1단계 결과(`03-baseline.md`, k6 요약의 `iterations.rate` 또는 TPS)와 비교해 두 부하가
  실제로 비슷한 수준이었는지 먼저 확인할 것. 크게 다르면(예: 2배 이상 차이) 숫자를
  "같은 부하에서의 비교"로 해석하지 말고, 그 차이 자체를 결과에 명시할 것 — 필요하면
  `ramping-arrival-rate` executor(요청 도착률을 직접 고정하는 열린 루프 모델)로 재측정하는
  것도 3단계 이후 고려 대상으로 남겨둔다.
- 1단계 베이스라인과 같은 한계(단일 상품 핫로우 경합, `03-baseline.md` "참고" 절)가
  이 측정에도 그대로 적용된다 — "클린한 베이스라인"이 아니라 "이 특정 경합 패턴에서의
  숫자"로 해석할 것.
- `saga_completion_duration`이 `POLL_TIMEOUT_MS`를 자주 넘긴다면, 그 자체가 부하 상황에서
  Outbox 릴레이나 Kafka 컨슈머가 밀리고 있다는 신호다 — `POLL_TIMEOUT_MS`를 늘려 재측정하기
  전에 먼저 원인(릴레이 폴링 주기, 컨슈머 처리량)을 의심할 것. **실제로 이번 측정에서
  VUs=20은 원인이 컨슈머/릴레이가 아니라 로컬 머신의 CPU 경합이었다** — VUs=1에서는 Saga가
  2~4초 안에 정상 완료됐다("VUs별 부하 한계 탐색" 참고). 서버급 환경(3단계 이후 후보)에서
  재측정하면 VUs=20에서도 실패율 0%가 나올 가능성이 높다.
- `products`/`inventory` 마이그레이션에는 시드 데이터가 없다 — 신선한 DB에서 처음 측정하려면
  `product_id=1` 행을 수동으로 만들어야 한다(`docs/troubleshooting/06-saga-performance-measurement.md`
  참고). `scripts/measure-saga-baseline.sh`는 이 행이 없으면 `UPDATE 0`으로 조용히 넘어가지
  않고 즉시 에러로 종료하도록 되어 있다(CodeRabbit 리뷰, PR #82).
- 3단계 종합 벤치마크 리포트(3.12)도 이 비교표와 함께 참조한다.
