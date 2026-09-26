# 1.13 — 재고 락 4종 성능 비교

로드맵 1.11~1.13. 완료 기준: "락 4종 성능 비교표가 숫자로 존재".

## 측정 환경 (실행 전 반드시 채울 것)

| 항목 | 값 |
|---|---|
| Hikari `maximum-pool-size` | ← `application-dev.yml` 값 기록 (락 비교의 숨은 변수, CLAUDE.md) |
| 대상 | inventory-service (8083), `POST /api/inventory/{id}/reserve` (3.12 신설 — 동기 전용 벤치마크 엔드포인트) |
| VUs / Duration | 50 / 30s (`scripts/benchmark-lock-strategies.sh` 기본값) |
| 초기 재고 | 100000 (재고 소진 자체가 목적이 아니라 넉넉하게 — 3.12, CodeRabbit 리뷰 PR #97) |
| 반복 횟수 | 워밍업 1회 + 측정 3회, 중앙값 사용 (CLAUDE.md 측정 규칙) |
| 실행 위치 | 로컬 Docker (CI 러너 아님 — 부록 C) |

## 실행 방법

```bash
docker compose -f docker-compose.yml up -d
set -a && source ./.env && set +a
./scripts/benchmark-lock-strategies.sh
```

## 결과

> 이 세션은 Docker가 없는 원격 컨테이너라 실제 수치를 측정할 수 없다. 아래는 채워야 할
> 표의 틀이다. `benchmarks/raw/<전략>-run<N>.json`(k6 summary export, 3회)의 중앙값을 읽어 채운다.
> `실패율(5xx)`은 `http_req_failed`가 아니라 각 summary의 `server_error_rate.rate`를 쓴다 —
> 재고가 소진되면 정상 응답인 409도 `http_req_failed`에 섞여 수치가 부풀려진다 (CodeRabbit 리뷰, PR #97).

| 전략 | TPS | p50 (ms) | p95 (ms) | p99 (ms) | 실패율(5xx) | 데드락 발생 |
|---|---|---|---|---|---|---|
| NONE (락 없음) | TODO | TODO | TODO | TODO | TODO | TODO |
| PESSIMISTIC | TODO | TODO | TODO | TODO | TODO | TODO |
| OPTIMISTIC (재시도 3회) | TODO | TODO | TODO | TODO | TODO | TODO |
| DISTRIBUTED | TODO | TODO | TODO | TODO | TODO | TODO |

## 관찰 메모 (측정 후 채울 것)

- NONE은 재고 초과 판매가 실제로 발생하는지 (`InventoryConcurrencyTest`의 재현과 일치하는지) —
  초기 재고를 넉넉히 잡았으므로(3.12) 소진 여부가 아니라 최종 `available`/`reserved` 값이
  차감 성공 건수와 일치하는지로 확인한다.
- PESSIMISTIC의 TPS가 낮다면 원인이 락 대기인지 Hikari 풀 대기인지 (1.13 TODO, 부록 C)
- OPTIMISTIC의 실패율이 높다면 경합률 대비 재시도 횟수(1.14)가 부족한 것인지
- DISTRIBUTED가 PESSIMISTIC보다 느리다면 Redis 왕복 비용 때문인지
