# 3.12 — 서킷 유무 성능 비교

로드맵 3.12. `ResilientMockPgGateway`(Retry+CircuitBreaker+TimeLimiter+Bulkhead, 3.3~3.6)를
켠 경우와 끈 경우(3.9의 "무방어" 실험)를 같은 부하 프로파일(`PROFILE=load`, VUs=20/60s)로
비교한다.

## 측정 환경

| 항목 | 값 |
|---|---|
| 대상 | 전체 스택(order/payment/inventory/notification-service + mock-pg-server) |
| 부하 | `k6/saga-order-flow.js --env PROFILE=load` (VUs=20, Duration=60s — 3.10/3.11과 동일) |
| 무방어 버전 | 로컬 전용 `experiment/3.12-no-defense` 브랜치(커밋/푸시 안 함) — `ResilientMockPgGateway.requestPayment`가 즉시 `mockPgClient.requestPayment`로 우회, `timelimiter.instances.mockPg.timeout-duration`을 3s→30s로 상향 |
| 방어 있음 버전 | `claude/sharp-cannon-3biixh`(병합 대상 브랜치) 그대로 — Retry/CircuitBreaker/TimeLimiter(3s)/Bulkhead 전부 활성 |
| 반복 | 워밍업 1회(10s) + 측정 3회(60s), 중앙값 |

## 결과 — 무방어 (서킷 없음)

로컬(Windows)에서 `experiment/3.12-no-defense` 브랜치로 측정.

| 지표 | Run1 | Run2 | Run3 | 중앙값 |
|---|---|---|---|---|
| TPS (http_reqs/s) | 18.75 | 18.48 | 43.43 | **18.75/s** |
| order_api p50 (ms) | 667 | 1,460 | 246 | **667** |
| order_api p95 (ms) | 5,350 | 8,400 | 1,560 | **5,350** |
| saga_completion p50 (s) | 12.04 | 3.30 | 9.20 | **9.20** |
| saga_completion p95 (s) | 14.77 | 3.40 | 13.98 | **13.98** |
| Saga 타임아웃(15s) 내 완료율 | 16.3%(14/86) | 2.4%(2/82) | 82.2%(106/129) | **16.3%** |
| http_req_failed | 0% | 0% | 0% | 0% |

(원본: `benchmarks/raw/no-defense-load-run{1,2,3}.json`, 2026-09-26 로컬 측정 — 이 브랜치는
커밋되지 않았으므로 이 표가 유일한 기록이다)

## 관찰 메모 (실측)

- **3회차 간 변동폭이 이 프로젝트에서 측정한 모든 지표 중 가장 크다.** Saga 타임아웃 내
  완료율이 2.4% → 16.3% → 82.2%로, 최악과 최선 회차 사이에 **34배** 차이가 난다. TPS도
  18.48~43.43/s로 2.3배 차이. 이 실험은 payment-service를 한 번만 재기동하고 그 위에서
  k6를 3번 연속 돌렸으므로(전략 전환이 없어 서비스 재시작 없이 순차 측정), 회차가
  진행될수록 JIT 컴파일/커넥션 풀/Kafka 컨슈머가 데워지면서 3회차가 가장 좋게 나왔을
  가능성이 크다 — 하지만 그것만으로 34배 차이를 설명하기엔 부족해 보이고, 로컬 환경
  노이즈(06-cache.md에서 이미 확인한 것과 같은 종류)도 상당히 섞여 있을 것이다.
- **그럼에도 방향성 자체는 3.9의 발견과 일치한다.** 3.9(수동 순차 측정, 10건)에서 이미
  "무방어 상태에서는 Kafka 리스너 스레드(concurrency=1)가 병목이라 요청이 사실상
  직렬화된다"는 걸 확인했다 — 이번 k6 부하 측정(VUs=20 동시)에서도 Saga 완료가
  대부분(중앙값 기준 83.7%) 15초 타임아웃을 넘긴다는 점이 그 결론과 방향이 같다.
- **"방어 있음" 쪽과의 비교값은 별도 절에 채운다** — 같은 세션 내에서 재측정한 값을
  써야 04-saga-comparison.md/06-cache.md에서 이미 확인한 "세션 간 노이즈" 문제를
  피할 수 있다 (아래 TODO).

## 결과 — 방어 있음 (서킷 있음)

> TODO — `claude/sharp-cannon-3biixh`(무방어 실험 이전 상태)로 복귀한 뒤, 같은 세션에서
> 바로 `PROFILE=load`를 3회 재측정해서 채운다. 3.10/3.11에서 이미 한 번 측정했지만(로드맵
> 노트: "load(VU 20)부터 주문 생성 API 평균 6.74초"), 그 원시 데이터가 이 문서에 기록되지
> 않았고 세션도 달라 이 표와 나란히 비교하기엔 근거가 약하다.

| 지표 | Run1 | Run2 | Run3 | 중앙값 |
|---|---|---|---|---|
| TPS (http_reqs/s) | TODO | TODO | TODO | TODO |
| order_api p50 (ms) | TODO | TODO | TODO | TODO |
| order_api p95 (ms) | TODO | TODO | TODO | TODO |
| saga_completion p50 (s) | TODO | TODO | TODO | TODO |
| saga_completion p95 (s) | TODO | TODO | TODO | TODO |
| Saga 타임아웃(15s) 내 완료율 | TODO | TODO | TODO | TODO |
| http_req_failed | TODO | TODO | TODO | TODO |
