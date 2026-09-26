# 1.14 — 낙관적 락 재시도 횟수별 성능 곡선

로드맵 1.14. `inventory.lock-strategy=OPTIMISTIC` 고정, `inventory.optimistic-lock.max-retries`만
1/3/5/10으로 바꿔가며 경합률에 따른 성능을 비교한다.

## 측정 환경

| 항목 | 값 |
|---|---|
| Hikari `maximum-pool-size` | ← `application-dev.yml` 값 기록 |
| 대상 | inventory-service (8083), `POST /api/inventory/{id}/reserve` (3.12 신설 — 동기 전용 벤치마크 엔드포인트) |
| VUs / Duration | 50 / 30s |
| 초기 재고 | 100000 (재고 소진 자체가 목적이 아니라 넉넉하게 — 3.12, CodeRabbit 리뷰 PR #97) |
| 반복 | 워밍업 1회 + 측정 3회, 중앙값 |

## 실행 방법

```bash
docker compose -f docker-compose.yml up -d
set -a && source ./.env && set +a
./scripts/benchmark-optimistic-retries.sh
```

## 결과

> 이 세션은 Docker가 없는 원격 컨테이너라 실제 수치를 측정할 수 없다. 로컬에서
> `scripts/benchmark-optimistic-retries.sh` 실행 후 `benchmarks/raw/optimistic-retries-*-run*.json`
> (3회)의 중앙값을 읽어 채운다. `실패율(재시도 소진)`은 각 summary의 `server_error_rate.rate`
> (503 — 재시도 소진/락 타임아웃)를 쓴다 — `http_req_failed`를 쓰면 재고 소진 시 정상 응답인
> 409까지 섞인다 (CodeRabbit 리뷰, PR #97).

| max-retries | TPS | p50 (ms) | p95 (ms) | p99 (ms) | 실패율(재시도 소진) |
|---|---|---|---|---|---|
| 1 | TODO | TODO | TODO | TODO | TODO |
| 3 | TODO | TODO | TODO | TODO | TODO |
| 5 | TODO | TODO | TODO | TODO | TODO |
| 10 | TODO | TODO | TODO | TODO | TODO |

## 예상되는 패턴 (측정 후 실제 값과 비교)

재시도 횟수가 늘수록 실패율은 낮아지지만 p95는 늘어난다 — 경합이 심할 때 재시도 자체가
추가 지연이기 때문. 어느 지점부터 "재시도를 늘려도 실패율 개선이 미미한데 p95만 나빠지는"
역전이 나타나는지가 이 실험의 핵심 관찰 포인트다.
