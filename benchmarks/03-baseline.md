# 1.21 — 베이스라인 측정

로드맵 1.21. 이후 모든 단계(캐싱, 서킷 브레이커, MSA 전환)의 성능 비교는 이 숫자를
기준으로 한다. 시나리오는 1.20의 `k6/order-payment-flow.js`(주문 생성 → 결제 요청).

## 측정 환경

| 항목 | 값 |
|---|---|
| Hikari `maximum-pool-size` | 50 (로컬에서 20 → 50으로 변경, 커밋되지 않은 로컬 설정. 저장소 기본값은 20) |
| `inventory.lock-strategy` | OPTIMISTIC (기본값) |
| VUs / Duration | 20 / 60s |
| 반복 | 워밍업 1회 + 측정 3회, 중앙값 |
| 실행 위치 | 로컬 Windows, PowerShell (`scripts/measure-baseline.ps1`) |
| 대상 상품 | 단일 상품 (`PRODUCT_ID=1`, 재고 100000) — 아래 참고 참조 |

## 실행 방법

`scripts/measure-baseline.sh`/`.ps1`은 Hikari 풀 크기를 오버라이드하지 않는다 —
`bootRun`은 `application-dev.yml`의 커밋된 기본값(20)을 그대로 쓴다. 아래 결과처럼
pool=50 조건을 재현하려면 `bootRun` 실행 **전에** `application-dev.yml`의
`spring.datasource.hikari.maximum-pool-size`를 50으로 직접 고쳐야 한다(수동 오버라이드,
아직 커밋된 기본값은 아님 — 1.13 TODO대로 이 값 자체를 실험 변수로 정식화하는 건 후속 작업).

```bash
# application-dev.yml의 maximum-pool-size를 50으로 수정한 뒤:
docker compose -f docker-compose.yml up -d
./gradlew mockPgRun &
./gradlew bootRun &
./scripts/measure-baseline.sh
```

Windows(PowerShell 5.1, Windows 기본 제공 `powershell.exe` — PowerShell 7/Core 아님):

```powershell
# application-dev.yml의 maximum-pool-size를 50으로 수정한 뒤:
docker compose -f docker-compose.yml up -d
Start-Job { .\gradlew.bat mockPgRun }
Start-Job { .\gradlew.bat bootRun }
.\scripts\measure-baseline.ps1
```

## 결과

3회 측정 원본:

| 회차 | TPS | med (p50) | p95 | 에러율 |
|---|---|---|---|---|
| 1 | 10.75/s | 1.52s | 4.10s | 39.36% |
| 2 | 9.23/s | 1.63s | 5.54s | 41.29% |
| 3 | 4.87/s | 2.12s | 13.81s | 40.54% |

중앙값(지표별):

| 지표 | 값 |
|---|---|
| TPS | 9.23/s |
| p50 | 1.63s |
| p95 | 5.54s |
| p99 | 미측정 (k6 기본 요약엔 p90/p95까지만 나옴. `--summary-trend-stats`는 기존 목록에 추가가 아니라 전체를 대체하므로, 추후 측정 시 `--summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)"`처럼 전체 목록으로 지정해야 함) |
| 에러율 | 40.54% |

## 참고

- **이 숫자를 "클린한 베이스라인"으로 일반화하면 안 된다.** `order-payment-flow.js`가
  `PRODUCT_ID=1` 하나만 고정으로 찌르기 때문에, 재고(100000)는 충분해도 VUS 20개가
  같은 행의 낙관적 락 버전을 두고 계속 충돌한다. 에러율 ~40%는 재고 부족이 아니라
  이 "단일 상품 집중 경합" 구조에서 `max-retries=3`으로는 다 못 이기는 요청 비율이다.
  즉 이 표는 "낙관적 락이 실무에서 얼마나 실패하는가"가 아니라 "단일 핫로우 경합 +
  낮은 재시도 횟수 조합의 실패율"을 보여준다.
- **회차가 갈수록(1→3) TPS가 10.75→4.87로 떨어지고 p95가 4.1s→13.81s로 늘어난다.**
  `Reset-Inventory`가 `stock` 값은 되돌리지만 PostgreSQL MVCC 특성상 같은 행에 대한
  반복 UPDATE(성공 + 재시도 실패 포함)가 쌓은 dead tuple은 회차 사이에 정리되지 않는다.
  유력 가설은 autovacuum이 못 따라가면서 해당 행 조회/갱신 비용이 누적된 것 — 로컬에서
  `SELECT n_live_tup, n_dead_tup, last_autovacuum FROM pg_stat_user_tables WHERE
  relname='inventory';`로 확인 가능. 아직 검증되지 않은 가설이며, 원인이 이것이라면
  `VACUUM inventory;` 후 재측정 시 TPS가 회복되는지로 확인할 수 있다.
- 후속 작업(TODO): 위 두 한계를 없앤 "진짜 클린 베이스라인"을 별도로 잡으려면
  (1) 여러 상품 ID에 트래픽을 분산시키거나, (2) 회차 사이에 `VACUUM`을 끼워 넣도록
  벤치마크 스크립트를 개선해야 한다. 이번 PR 범위 밖의 별도 작업으로 남긴다.
- 2단계 완료 후 같은 시나리오로 재측정해 "분리 후 latency가 나빠지는" 트레이드오프를
  숫자로 남긴다 (2.20).
- 3단계 종합 벤치마크 리포트(3.12)도 이 베이스라인과 비교한다.
