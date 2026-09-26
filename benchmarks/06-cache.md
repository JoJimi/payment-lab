# 3.12 — 캐시 유무 성능 비교

로드맵 3.12. `ProductService`(1.15)의 Look-aside 캐싱을 켠 경우/끈 경우를 같은 애플리케이션에서
동시에 비교한다.

## 측정 환경

| 항목 | 값 |
|---|---|
| 대상 | inventory-service (8083), `GET /api/products/{id}`(캐시) vs `GET /api/products/{id}/uncached`(무캐시, 3.12 신규) |
| 캐시 TTL (`cache.products.ttl-ms`) | ← `application.yml` 값 기록 (기본 60000ms) |
| VUs / Duration | 50 / 30s (`scripts/benchmark-cache.sh` 기본값) |
| 반복 횟수 | 워밍업 1회 + 측정 3회, 중앙값 사용 (CLAUDE.md 측정 규칙) |
| 실행 위치 | 로컬 Docker (CI 러너 아님 — 부록 C) |

## 실행 방법

```bash
docker compose -f docker-compose.yml up -d
./gradlew inventory-service:bootRun &
./scripts/benchmark-cache.sh
```

## 결과

로컬(Windows, Docker Desktop)에서 `scripts/benchmark-cache.sh` 실행. 워밍업 1회 + 측정 3회의
중앙값.

| 모드 | TPS | p50 (ms) | p95 (ms) | p99 (ms) | 에러율 |
|---|---|---|---|---|---|
| 캐시 없음 (매 요청 DB 조회) | 375.31/s | 114.44 | 204.86 | 296.29 | 0.00% |
| 캐시 있음 (Redisson, sync=true) | 455.84/s | 55.73 | 201.46 | 564.86 | 0.00% |

(원본: `benchmarks/raw/cache-{uncached,cached}-run{1,2,3}.json`, 2026-09-26 로컬 측정)

## 관찰 메모 (실측)

- **TPS/p50은 예상대로 캐시가 이긴다.** 처리량 +21%(375.31→455.84/s), p50은 절반 이하로
  줄었다(114.44ms→55.73ms) — Redis 조회가 PK 단건 조회보다 확실히 빠르다.
- **p95는 사실상 동률이다** (204.86ms vs 201.46ms, 2% 이내 차이) — 이 부하 수준(VUs=50)
  에서는 캐시가 "평균은 빠르게 하지만 상위 5%는 못 줄이는" 효과만 낸다.
- **미리 세워둔 가설대로 p99는 캐시 있음 쪽이 오히려 더 나쁘다** — 296.29ms(무캐시) →
  564.86ms(캐시, **약 1.9배**). TPS/p50이 개선되는데 꼬리 지연만 나빠지는 패턴이 정확히
  나타났다 — Redisson 커넥션 풀(기본 크기 제한)이 VUs=50의 동시 요청을 다 못 받아 일부가
  풀 대기에 걸리는 것으로 보인다. 무캐시 쪽은 병목이 Hikari 커넥션 풀(DB)인데, 이쪽이
  이 부하 수준에서는 더 여유로웠다는 뜻이기도 하다.
- **`sync=true`의 스탬피드 방지 비용은 이번 측정에서 드러나지 않았다.** TTL(60s)이 각 측정
  구간(30s)보다 길어 워밍업 이후 캐시 미스가 사실상 없었으므로, "TTL 만료 순간 첫 스레드가
  나머지를 블로킹"하는 비용은 이 벤치마크가 애초에 관측할 수 있는 구간이 아니다 — 그건
  1.17의 `ProductCacheStampedeTest`가 별도로 다루는 시나리오다.
- **결론**: 이 서비스의 상품 조회 캐싱은 "평균 응답을 빠르게, 처리량을 늘리는" 데는 확실히
  효과가 있지만 공짜가 아니다 — Redisson 풀 설정을 그대로 두면 부하가 몰릴 때 꼬리 지연이
  캐시 없을 때보다 나빠질 수 있다는 트레이드오프가 실측으로 확인됐다.
