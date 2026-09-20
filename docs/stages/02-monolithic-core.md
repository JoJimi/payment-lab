# 1단계 — 모놀리식 결제 코어

기간: 2026-09-20 ~ (진행 중)

## 목표

결제 도메인의 정합성 문제를 단일 애플리케이션 안에서 전부 겪는다. 분산 문제(Saga,
Outbox, 서비스 분리)는 2단계로 미룬다.

## 완료 기준 달성 현황

| 기준 | 상태 | 증거 |
|---|---|---|
| 중복 결제 요청 100건 → 승인 1건, 동일 응답 (자동화) | ✅ | `PaymentIdempotencyConcurrencyTest` (CI에서 Testcontainers로 검증, 이 세션은 Docker 없어 로컬 미실행) |
| 재고 초과 판매 0건 (동시성 테스트) | ✅ | `InventoryConcurrencyTest` — PESSIMISTIC/OPTIMISTIC/DISTRIBUTED 3종 모두 정확히 100건 성공 단언. NONE(대조군)은 반대로 Lost Update가 재현됨을 단언 |
| 락 4종 성능 비교표가 `benchmarks/01-lock-strategies.md`에 숫자로 존재 | ⏳ | 스캐폴딩(k6 시나리오 + 실행 스크립트 + 표 틀)까지 완료. **실측은 로컬 Docker 필요** — 이 세션은 원격 컨테이너라 실행 불가 |
| Grafana에서 TPS/p95 실시간 확인 | ⏳ | `docker-compose.observability.yml` + 대시보드 프로비저닝 완료. **로컬에서 앱 기동 후 육안 확인 필요** |
| 전부 CI에서 통과 (로컬 통과만은 불인정) | ⏳ | 이 문서 작성 시점까지 브랜치 push만 했고 PR을 열지 않아 `pr-check.yml`(트리거: `pull_request`)이 아직 돌지 않음. 1.22(CI 10분 이내) 검증도 PR이 있어야 실측 가능 |

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

## 이 세션에서 실행 불가능했던 것 (원격 컨테이너 — Docker 없음)

- Testcontainers 기반 통합 테스트 전체 실행 (CI에서 검증 예정)
- 락 4종/낙관적 재시도/베이스라인 벤치마크 실측 (`benchmarks/0{1,2,3}-*.md`의 TODO)
- Grafana 대시보드 육안 확인
- **PR CI 실행 시간(1.22) 실측** — `pr-check.yml`은 `pull_request` 트리거라 PR을 열어야
  `build-test` 잡이 실제로 돈다. 현재 Testcontainers를 쓰는 테스트 클래스가 5개
  (`QuietFailureRegressionTest`, `PaymentIdempotencyConcurrencyTest`,
  `PaymentIdempotencyRedisDownTest`, `InventoryConcurrencyTest`,
  `ProductCacheStampedeTest`)로, 각각 독립적으로 Postgres+Redis 컨테이너를 띄운다.
  10분을 넘기면 부록 E-3(컨테이너 공유 또는 무거운 테스트 분리)을 적용해야 한다.

## 다음 단계

PR을 열어 실제 CI(Docker 포함) 결과로 1.9/1.10/1.12/1.17과 1.22(실행 시간)를 확정하고,
로컬 환경에서 세 벤치마크 문서의 TODO를 채우면 1단계가 완료된다.
