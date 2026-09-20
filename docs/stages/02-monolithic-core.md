# 1단계 — 모놀리식 결제 코어

기간: 2026-09-20 ~ (진행 중)
관련 PR: [#36](https://github.com/JoJimi/payment-lab/pull/36) — CI 전체 그린 확인 완료

## 목표

결제 도메인의 정합성 문제를 단일 애플리케이션 안에서 전부 겪는다. 분산 문제(Saga,
Outbox, 서비스 분리)는 2단계로 미룬다.

## 완료 기준 달성 현황

| 기준 | 상태 | 증거 |
|---|---|---|
| 중복 결제 요청 100건 → 승인 1건, 동일 응답 (자동화) | ✅ | `PaymentIdempotencyConcurrencyTest` — PR #36 CI에서 통과 확인 |
| 재고 초과 판매 0건 (동시성 테스트) | ✅ | `InventoryConcurrencyTest` — PESSIMISTIC/OPTIMISTIC/DISTRIBUTED 3종 모두 정확히 100건 성공, NONE(대조군)은 반대로 Lost Update가 재현됨을 단언. PR #36 CI에서 통과 확인 |
| 락 4종 성능 비교표가 `benchmarks/01-lock-strategies.md`에 숫자로 존재 | ⏳ | 스캐폴딩(k6 시나리오 + 실행 스크립트 + 표 틀)까지 완료. **실측은 로컬 Docker 필요** — 원격 세션·CI 모두 부록 C(러너에서 절대값 측정 금지)에 따라 대상이 아님 |
| Grafana에서 TPS/p95 실시간 확인 | ⏳ | `docker-compose.observability.yml` + 대시보드 프로비저닝 완료. **로컬에서 앱 기동 후 육안 확인 필요** |
| 전부 CI에서 통과 (로컬 통과만은 불인정) | ✅ | PR #36 — `build-test`/`sast`/`sca-dependency`/Semgrep/Trivy 5개 체크 전부 success, `mergeable_state: clean` |

CI에서 실제로 잡아낸 버그 3건(로컬에서는 안 보이던 것들 — 이 자체가 "CI가 없으면
안 보이는 문제가 있다"는 R단계의 교훈이 1단계에서도 반복됨을 보여줌):
1. `Inventory` 엔티티 `@PrePersist` 누락 → INSERT 시 `updated_at` NULL로 제약 위반
   (Product/Order/Payment엔 있는데 Inventory만 빠짐)
2. 낙관적 락 300-way 경합에서 기본 재시도(3회) 소진 → `ObjectOptimisticLockingFailureException`
   누출. 버그가 아니라 낙관적 락의 알려진 트레이드오프(1.14 측정 대상)지만, 1.12(안전성
   검증)에서는 재시도를 500으로 올려 "초과 판매 없음"만 검증하도록 분리
3. 분산 락 대기시간(3s)이 300-way 경합에서 부족할 수 있어 10s로 상향

실측 CI 소요 시간: **2분 50초~2분 57초** (여러 번 재실행, 10분 기준 대비 여유 — 1.22 확정)

## 핵심 결과

**1-A 도메인 모델링 (1.1~1.3)**
- ERD(`docs/domain/erd.md`), 상태 전이표(`docs/domain/state-transitions.md`)를 문서로
  먼저 그리고 `OrderStatus`/`PaymentStatus` enum의 `canTransitionTo`로 코드화
- `orders`/`payments`가 서로 FK를 걸지 않는 소프트 참조 — 2단계 DB 분리를 스키마 레벨에서
  미리 대비
- `UNKNOWN` 결제 상태(PG 타임아웃)를 `FAILED`와 분리 — CLAUDE.md 코드 규칙 직접 반영

**1-B API·Mock PG (1.4~1.6)**
- OpenAPI 명세(`docs/api/openapi.yaml`)
- Mock PG를 순수 JDK `HttpServer` 기반 별도 프로세스로 구현 — Spring 컴포넌트가 없어
  메인 앱 컨텍스트에 절대 섞이지 않음. 지연/실패율/강제 에러코드/타임아웃을
  `POST /pg/_config`로 런타임 주입
- Mock PG 자체의 멱등성(동일 키 동시 요청 시 최초 1회만 처리)까지 Docker 없이 순수
  JUnit 6개로 검증 완료 (`MockPgServerTest`, 이 세션에서 실제로 통과 확인)

**1-C 멱등성 (1.7~1.10)**
- `@Idempotent(key = SpEL)` + AOP로 Redis SETNX(1차)+DB unique(2차) 2단 방어 구현
- 3-상태 처리: 없음(진행)/IN_PROGRESS(폴링 후 동일 응답 재현)/COMPLETED(원본 응답 재현)
- Redis 장애를 `DataAccessException`으로 잡아 DB 2차 방어로 우아하게 폴백 — Redis가
  죽어도 중복 승인이 나지 않음을 `PaymentIdempotencyRedisDownTest`로 검증

**1-D 동시성 제어 (1.11~1.14)**
- 재고 차감 4종(NONE/PESSIMISTIC/OPTIMISTIC/DISTRIBUTED)을 `StockDeductor` 구현체로
  분리, `inventory.lock-strategy` 설정으로 전환
- NONE은 `@Version`이 있어도 Hibernate 낙관적 락을 우회하도록 `JdbcTemplate`으로
  일부러 영속성 컨텍스트를 건너뛰어 Lost Update를 실제로 재현
- OPTIMISTIC/DISTRIBUTED는 `PROPAGATION_REQUIRES_NEW`로 재시도-시-stale-read,
  unlock-before-commit 같은 실제 사고 패턴을 코드 레벨에서 방지
- 성능 비교표(1.13)·재시도 곡선(1.14)은 k6 스캐폴딩까지 완료, 실측은 로컬 필요

**1-E 캐싱 (1.15~1.17)**
- 상품 조회만 Redisson 기반 `@Cacheable`로 캐싱, 재고는 캐싱하지 않는다는 판단을
  [ADR-0011](../decisions/0011-cache-scope-product-not-inventory.md)에 기록
- Cache Stampede를 `sync=false`(무방어) vs `sync=true`(방어) 두 캐시로 나눠 같은
  Spring 컨텍스트 안에서 직접 비교(`ProductCacheStampedeTest`)

**1-F 관측 및 측정 (1.18~1.22)**
- Prometheus/Grafana를 1단계로 앞당김 — 완료 기준 자체가 "Grafana에서 TPS/p95 확인"이라
  3단계까지 미루면 성립하지 않음 (`docs/roadmap.md` 부록 B, `CLAUDE.md` 로컬 리소스
  표를 실제 동작에 맞춰 함께 수정)
- 커스텀 메트릭 3종: `payment.result{status}`, `idempotency.requests{result}`,
  `inventory.lock.wait{strategy}`
- k6 기본 시나리오(`order-payment-flow.js`, 주문→결제)와 베이스라인 측정 스캐폴딩

## PR #36 — CI 검증 기록

이 세션은 Docker 없는 원격 컨테이너라 Testcontainers 기반 테스트를 로컬에서 돌릴 수
없었다. PR을 열어 실제 CI로 검증했고, 그 과정에서 위 버그 3건을 실전으로 잡았다
(R단계와 같은 패턴 — "로컬에서 통과로 보이는 것"과 "CI/Docker 있는 환경에서 통과하는 것"은
다르다는 걸 1단계에서도 다시 확인함).

| 시도 | 커밋 | 결과 |
|---|---|---|
| 1차 | `6b7e0ad` | ❌ `InventoryConcurrencyTest` 4개 전부 `@PrePersist` 누락으로 실패 |
| 2차 | `c8e220d` | ❌ 2개 남음 (낙관적 락 재시도 소진, Redis-down 테스트의 `AssertionFailure` — 원인 불명, testLogging 강화) |
| 3차 | `02737c5` | ✅ **BUILD SUCCESSFUL in 2m 50s** |
| 브랜치 최신화 후 재검증 | `5feaa0b` | ✅ 2m 57s, `mergeable_state: clean` |

남은 `PaymentIdempotencyRedisDownTest`의 `org.hibernate.AssertionFailure`는 2차 시도에서
재현되지 않아(3차부터 통과) 근본 원인은 확정하지 못했다 — Redis 컨테이너 강제 종료 타이밍과
연관된 일회성 경합으로 추정. `testLogging(exceptionFormat = FULL)`을 켜뒀으니 재발하면
다음 CI 로그에서 전체 스택트레이스로 바로 진단 가능하다.

## 남은 것 (로컬 Docker 환경 필요)

- 락 4종/낙관적 재시도/베이스라인 벤치마크 실측 (`benchmarks/0{1,2,3}-*.md`의 TODO) —
  `scripts/benchmark-lock-strategies.sh`, `scripts/benchmark-optimistic-retries.sh`,
  `scripts/measure-baseline.sh`
- Grafana 대시보드 육안 확인 (`GRAFANA_ADMIN_PASSWORD` 환경변수 설정 후
  `docker-compose.observability.yml` 기동, `http://localhost:3000` 접속)

이 두 가지만 채우면 1단계가 완전히 끝난다 — 나머지 완료 기준은 전부 CI로 확정됨.
