# 2.20 — 1단계 대비 2단계 성능 비교 (템플릿, 미측정)

로드맵 2.20. 시나리오는 `k6/saga-order-flow.js`(주문 생성 → Saga 완료까지 폴링),
실행은 `scripts/measure-saga-baseline.sh`. 이 원격 세션은 Docker가 없어 직접 측정할 수
없다 — 아래는 로컬에서 채워 넣을 틀만 미리 준비해둔 것이다(`03-baseline.md`와 같은 형식).

## 측정 환경

| 항목 | 값 |
|---|---|
| 대상 | order-service(`:8081`) — payment/inventory/notification-service는 Kafka 이벤트로 간접 참여 |
| DLQ 재시도 | 500ms 간격, 최초 시도 포함 총 3회 (`common-kafka`, 2.16) |
| Saga 타임아웃 | 기본 10분(`app.saga.timeout-minutes`), 폴링 주기 30초 |
| VUs / Duration | 20 / 60s (1.21과 동일 — 단, 아래 "참고"의 부하 동등성 주의사항 참고) |
| 반복 | 워밍업 1회 + 측정 3회, 중앙값 |
| 대상 상품 | 단일 상품(`PRODUCT_ID=1`, 재고 100000) — 1.21과 동일한 한계(단일 핫로우 경합)가 그대로 적용됨 |
| `POLL_TIMEOUT_MS` | 15000 (기본값 — Saga 완료를 기다리는 최대 시간, 이 안에 안 끝나면 미완료로 집계) |

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

## 결과 (미측정 — 로컬에서 채울 것)

3회 측정 원본:

| 회차 | order_api p50 | order_api p95 | saga_completion p50 | saga_completion p95 | Saga 타임아웃 내 종결 실패율 | 실측 주문 생성률(iterations/s) |
|---|---|---|---|---|---|---|
| 1 | | | | | | |
| 2 | | | | | | |
| 3 | | | | | | |

마지막 열(`실측 주문 생성률`)은 `benchmarks/raw/saga-run*.json`의 `metrics.iterations.values.rate`
값을 그대로 옮겨 적을 것 — 아래 "참고"의 부하 동등성 주의사항 때문에 반드시 채워야 한다.

중앙값(지표별):

| 지표 | 값 |
|---|---|
| order_api_duration p50 | |
| order_api_duration p95 | |
| saga_completion_duration p50 | |
| saga_completion_duration p95 | |
| Saga 타임아웃 내 종결 실패율 | |

## 1단계 대비 비교 (미작성)

| 지표 | 1단계 (`03-baseline.md`) | 2단계 order_api | 2단계 saga_completion |
|---|---|---|---|
| p50 | 1.63s | | |
| p95 | 5.54s | | |
| TPS | 9.23/s | | |

**주의 — 1단계 p50/p95는 같은 범위의 지표가 아니다**(CodeRabbit 리뷰, PR #82).
`03-baseline.md`의 1.63s/5.54s는 `k6/order-payment-flow.js`가 별도 Trend 없이 남긴 k6
기본 `http_req_duration` 요약값이다 — 이건 "주문 요청 시작~결제 응답 완료"라는 반복(iteration)
단위 소요 시간이 아니라, **주문 생성 요청과 결제 요청 두 종류의 개별 HTTP 요청 시간을 하나의
분포로 섞은 값**이다. 반면 2단계 `saga_completion_duration`은 반복 전체(주문 요청 시작부터
Saga 종결까지)를 재는 커스텀 Trend다. 그래서 이 표의 p50/p95 비교는 "정확히 같은 범위의
숫자"가 아니라 참고용 근사치로만 볼 것 — 엄밀히 비교하려면 1단계 스크립트에도 주문
요청부터 결제 응답까지를 묶는 Trend를 추가해야 하는데, 그건 이미 완료된 1단계 산출물을
건드리는 일이라 이번 PR 범위 밖으로 남겨둔다. TPS(9.23/s)는 k6의 `iterations.rate`
기반이라 이 문제가 없다 — 2단계 쪽 TPS는 위 "실측 주문 생성률" 열과 직접 비교 가능하다.

`order_api_duration`은 1단계보다 빨라질 것으로 예상한다(주문 생성이 더 이상 결제 승인을
동기로 기다리지 않으므로) — 반면 `saga_completion_duration`은 Outbox 릴레이 폴링 주기 x
여러 홉이 더해져 1단계의 동기 왕복보다 느려질 가능성이 높다(로드맵 2.20 자체가 예고한
트레이드오프). 실측 후 이 예상이 맞는지, 그리고 얼마나 차이 나는지를 여기 기록할 것.

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
  전에 먼저 원인(릴레이 폴링 주기, 컨슈머 처리량)을 의심할 것.
- 3단계 종합 벤치마크 리포트(3.12)도 이 비교표와 함께 참조한다.
