# 1.21 — 베이스라인 측정

로드맵 1.21. 이후 모든 단계(캐싱, 서킷 브레이커, MSA 전환)의 성능 비교는 이 숫자를
기준으로 한다. 시나리오는 1.20의 `k6/order-payment-flow.js`(주문 생성 → 결제 요청).

## 측정 환경

| 항목 | 값 |
|---|---|
| Hikari `maximum-pool-size` | ← `application-dev.yml` 값 기록 |
| `inventory.lock-strategy` | ← 측정 시점 `application.yml` 기본값 기록 (기본 OPTIMISTIC) |
| VUs / Duration | 20 / 60s |
| 반복 | 워밍업 1회 + 측정 3회, 중앙값 |

## 실행 방법

```bash
docker compose -f docker-compose.yml up -d
./gradlew mockPgRun &
./gradlew bootRun &
./scripts/measure-baseline.sh
```

## 결과

> 이 세션은 Docker가 없는 원격 컨테이너라 실제 수치를 측정할 수 없다. 로컬에서
> `scripts/measure-baseline.sh` 실행 후 `benchmarks/raw/baseline-run*.json` 3개의
> 중앙값을 아래에 채운다.

| 지표 | 값 |
|---|---|
| TPS | TODO |
| p50 | TODO |
| p95 | TODO |
| p99 | TODO |
| 에러율 | TODO |

## 참고

- 2단계 완료 후 같은 시나리오로 재측정해 "분리 후 latency가 나빠지는" 트레이드오프를
  숫자로 남긴다 (2.20).
- 3단계 종합 벤치마크 리포트(3.12)도 이 베이스라인과 비교한다.
