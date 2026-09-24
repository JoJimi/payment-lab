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
| VUs / Duration | 20 / 60s (1.21과 동일 — 비교 가능성 유지) |
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

| 회차 | order_api p50 | order_api p95 | saga_completion p50 | saga_completion p95 | Saga 타임아웃 내 종결 실패율 |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |

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

`order_api_duration`은 1단계보다 빨라질 것으로 예상한다(주문 생성이 더 이상 결제 승인을
동기로 기다리지 않으므로) — 반면 `saga_completion_duration`은 Outbox 릴레이 폴링 주기 x
여러 홉이 더해져 1단계의 동기 왕복보다 느려질 가능성이 높다(로드맵 2.20 자체가 예고한
트레이드오프). 실측 후 이 예상이 맞는지, 그리고 얼마나 차이 나는지를 여기 기록할 것.

## 참고

- 1단계 베이스라인과 같은 한계(단일 상품 핫로우 경합, `03-baseline.md` "참고" 절)가
  이 측정에도 그대로 적용된다 — "클린한 베이스라인"이 아니라 "이 특정 경합 패턴에서의
  숫자"로 해석할 것.
- `saga_completion_duration`이 `POLL_TIMEOUT_MS`를 자주 넘긴다면, 그 자체가 부하 상황에서
  Outbox 릴레이나 Kafka 컨슈머가 밀리고 있다는 신호다 — `POLL_TIMEOUT_MS`를 늘려 재측정하기
  전에 먼저 원인(릴레이 폴링 주기, 컨슈머 처리량)을 의심할 것.
- 3단계 종합 벤치마크 리포트(3.12)도 이 비교표와 함께 참조한다.
