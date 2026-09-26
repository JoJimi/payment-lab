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

> 이 세션은 Docker가 없는 원격 컨테이너라 실제 수치를 측정할 수 없다. 아래는 채워야 할
> 표의 틀이다. `benchmarks/raw/cache-{uncached,cached}-run*.json`(k6 summary export, 각 3회)의
> 중앙값을 읽어 채운다.

| 모드 | TPS | p50 (ms) | p95 (ms) | p99 (ms) | 에러율 |
|---|---|---|---|---|---|
| 캐시 없음 (매 요청 DB 조회) | TODO | TODO | TODO | TODO | TODO |
| 캐시 있음 (Redisson, sync=true) | TODO | TODO | TODO | TODO | TODO |

## 관찰 메모 (측정 후 채울 것)

- 두 모드 다 같은 Postgres/Redis/애플리케이션 인스턴스를 공유하므로, 차이는 순수하게
  "요청마다 DB를 때리는가 vs Redis 캐시를 읽는가"에서만 나와야 한다 (부록 C: 한 번에
  한 개념만 켠다).
- 캐시 있음 쪽 p95가 캐시 없음보다 오히려 나쁘게 나온다면 Redis 왕복 자체의 비용(네트워크
  + 직렬화)이 이 부하 수준에서 단순 PK 조회보다 비싼 것인지 의심할 것 — TPS/p50이 개선되는데
  꼬리 지연(p99)만 나빠지는 패턴이면 Redisson 커넥션 풀 대기가 원인일 수 있다.
- 1.17(Cache Stampede)에서 이미 확인했듯 `sync = true`는 TTL 만료 순간의 동시 미스를
  막아주지만, 그 대가(첫 스레드가 나머지를 블로킹)가 이 부하 수준의 p99에 드러나는지도 볼 것.
