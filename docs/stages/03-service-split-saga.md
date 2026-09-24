# 2단계 — 서비스 분리 + Kafka Saga

기간: 2026-09-22 ~ (진행 중 — 2-A/2-B/2-C 완료, 2-D 검증 남음)
관련 PR: [#46](https://github.com/JoJimi/payment-lab/pull/46) ~ [#74](https://github.com/JoJimi/payment-lab/pull/74) (2.1~2.16, 16개)

## 목표

하나의 트랜잭션으로 묶여 있던 흐름을 4개 서비스(order/payment/inventory/notification)로
쪼개고, 트랜잭션 경계가 사라졌을 때 생기는 문제들(부분 실패, 중복 소비, 순서 역전, 타임아웃)을
전부 겪는다.

## 완료 기준 달성 현황

로드맵이 정한 2단계 완료 기준 4개 중, 이 문서를 쓰는 시점에 실제로 충족된 건 1개뿐이다 —
나머지 3개는 2-D(2.17~2.20, 검증 단계)가 아직 시작 전이라 판단할 근거 자체가 없다.

| 기준 | 상태 | 증거 |
|---|---|---|
| Payment Service 강제 종료 후 재기동 → 주문 100건 전부 완료/보상 (유실 0, 이중 처리 0) | ⏳ | 미착수 — 2.17(장애 주입 테스트)의 몫. 다운스트림이 죽어 응답이 안 오는 상황 자체는 2.15(Saga 타임아웃)가 이미 다루지만, "강제 종료 → 재기동 → 100건 검증"이라는 실측 시나리오는 아직 안 돌렸다 |
| 재고 초과 판매 0건 (분산 환경에서도) | ⏳ | 1단계 수준(`InventoryConcurrencyTest`, 단일 프로세스 내 300-way 경합)만 확인됨. "분산 환경"이라는 조건은 여러 인스턴스/서비스가 동시에 같은 재고를 다투는 시나리오를 뜻하는데, 2-D 전까지는 검증 대상이 아니었다 |
| Saga 상태 조회 API로 임의 주문의 진행 단계를 추적 가능 | ⏳ | 미구현 — `saga_instance`/`saga_step` 테이블과 상태 전이 로직(2.11)은 있지만, 이걸 노출하는 REST 엔드포인트는 로드맵 2-A~2-C 어디에도 태스크로 없었다. 2-D 착수 전에 필요성 재확인 필요 (아래 "다음 단계로 넘기는 숙제" 참고) |
| `KafkaTemplate` 직접 호출 PR이 Semgrep에 의해 차단됨 (검증 완료) | ✅ | `.semgrep/outbox-required.yml`(2.10) — `semgrep --test` 통과 + 의도적 위반 코드로 실제 차단 확인(`troubleshooting/04-msa-split.md` §15) |

## 핵심 결과

- **PR 16개**(#46~#74, 2.1~2.16 각 1:1), 전부 CodeRabbit 리뷰 반영 후 병합. 이 중 1건(#77/#78
  범위, 2.2)은 CodeRabbit이 잡은 보안 이슈(DB 자격증명 평문 커밋)를 같은 PR에서 바로 수정.
- **모듈 1개 → 11개**: 모놀리식 `cs_study` 하나였던 것이 `common-*` 6개(event/idempotency/
  inbox/kafka/outbox/web) + 서비스 4개(order/payment/inventory/notification) +
  테스트 픽스처 1개(mock-pg-server)로 늘었다.
- **Kafka 토픽 8개 = 이벤트 타입 8개** (1:1 대응, `EventType` enum으로 고정):
  `order.created`, `payment.requested`, `payment.completed`, `payment.failed`,
  `inventory.reserved`, `inventory.failed`, `order.cancelled`, `notification.requested`.
- **테스트**: 전체 테스트 파일 약 31개, `@Test` 메서드 109개. Testcontainers 필요(Postgres/
  Redis) 20개 파일, `@EmbeddedKafka` 필요 7개 파일, 나머지는 Docker 불필요한 순수 단위
  테스트 — 이 비율이 이 원격 세션(Docker 없음)에서 "무엇까지 직접 검증했고 무엇을 CI에
  넘겼는지"를 그대로 반영한다(각 PR 설명에 매번 명시).
- **DLQ 재시도**: 500ms 간격, 최초 시도 포함 총 3회 — payment-service의 Mock PG 재시도
  설정값과 의도적으로 맞췄다(2.16).
- **Saga 타임아웃**: 기본 10분(`app.saga.timeout-minutes`), 회수 스케줄러 폴링 주기 30초
  (`app.saga.timeout.scheduler.fixed-delay-ms`) — 둘 다 테스트에서 주입 가능하도록 설정값으로
  뺐다(2.15).
- **CI 실행 시간 추이**(`build-test`, `PR Check` 워크플로 실측): 멀티모듈 전환 직후
  (2.1~2.6, DB 분리까지만) 약 **3.5~4분** → Kafka/Outbox/Inbox가 들어온 뒤(2.8~2.10)
  약 **8~9분** → 4개 서비스가 전부 Saga로 엮이는 통합 테스트가 쌓인 뒤(2.11~2.16) 약
  **13~15분**. 1단계 종료 시점(2분 50초~2분 57초, `stages/02-monolithic-core.md`)과 비교하면
  2단계를 거치며 CI 시간이 약 5배 늘었다 — 서비스/토픽/통합 테스트가 늘어난 만큼 자연스러운
  증가이고, 아직 로드맵의 10분 기준을 위협하는 수준은 아니다(2.4 검토 기록,
  `docs/ci-cd.md` 참고).

## 이 단계에서 내린 결정

이 프로젝트는 아직 ADR 파일을 쓰지 않는다 — 결정과 그 근거는 트러블슈팅 문서에 직접
남기는 방식을 택했다(각 절 자체가 "왜 이렇게 했는가"의 기록이다). 그중 이후 단계에
영향을 주는 굵직한 결정만 추리면:

- **서비스 모듈끼리는 절대 서로 참조하지 않는다** — 2.1~2.3에서 동기 호출/죽은 포트
  인터페이스를 전부 제거하고, 이후 모든 상호작용은 `common-event`의 이벤트 계약을 통해서만
  연결한다(`04-msa-split.md` §1, §11).
- **도메인 중립 인프라 로직은 `common-*` 모듈로 뽑고, 그 모듈 스스로 테스트로 증명한다** —
  `common-idempotency`(1단계 이관) → `common-outbox`(2.8) → `common-inbox`(2.9) →
  `common-kafka`(2.16) 순으로 같은 패턴을 반복했다.
- **Outbox 발행은 `OutboxRelay` 한 곳으로만 강제하고, 이를 리뷰가 아니라 Semgrep으로
  기계적으로 막는다**(2.10) — "리뷰어가 매번 눈으로 확인"은 결국 놓친다는 판단.
- **가격은 클라이언트가 보낸다** — Payment Service가 재고를 모르듯 Order Service도 상품
  가격의 진실 공급원이 아니라는 경계를 지키기 위해, 동기 가격 조회 대신 이 방식을 택했다
  (가격 위변조 방어는 2-D 이후로 명시적으로 미룸, `05-saga-orchestration.md` §2).
- **재고 예약(`reserve`)과 확정(`confirm`)을 일단 같은 트랜잭션에서 바로 잇는다** —
  2.13에서 다시 검토했지만 "알림 실패는 보상 안 함" 방침상 예약 성공 시점이 사실상 Saga
  성공 확정이라, 지금은 갈라놓을 이유가 없다고 판단(`05-saga-orchestration.md` §2).
- **낙관적 락은 필요해질 때 추가한다** — 2.11에서 CodeRabbit이 `saga_instance`/`saga_step`에
  `@Version`을 제안했을 때 "아직 동시 쓰기 주체가 없다"며 반려했고, 2.13에서 실제로 여러
  리스너가 같은 행을 건드리게 되자 2.14에서 약속대로 추가했다(`05-saga-orchestration.md` §4).
- **PAYMENT/UNKNOWN 재조회는 2단계에서 닫지 않는다** — Saga 타임아웃(2.15)과 취소 TOCTOU
  둘 다 "Mock PG가 실제로 승인했는지 재조회"가 선행돼야 제대로 닫히는데, 그건 로드맵 3.4의
  몫이라 판단해 알려진 한계로 문서화만 하고 이슈 [#72](https://github.com/JoJimi/payment-lab/issues/72)로
  이월했다(`05-saga-orchestration.md` §5).

## 막혔던 것

- [`troubleshooting/04-msa-split.md`](../troubleshooting/04-msa-split.md) — Gradle
  멀티모듈 전환(2.1)과 Kafka 기반 인프라(2-B, 2.5~2.10) 과정에서 겪은 문제. Jackson 3
  groupId 변경, `scanBasePackages`와 `@EnableJpaRepositories`의 스캔 범위 불일치,
  `@SpringBootTest`의 `TestTypeExcludeFilter`, Outbox 컬럼 길이/트랜잭션 전파 버그,
  컨슈머 멱등성 TOCTOU 등.
- [`troubleshooting/05-saga-orchestration.md`](../troubleshooting/05-saga-orchestration.md) —
  Saga 오케스트레이션(2-C, 2.11~2.16) 과정에서 겪은 문제. notification-service의 Boot 4
  Kafka 자동구성 조용한 실패, Saga 타임아웃이 이미 확정된 재고를 놓칠 뻔한 레이스, DLT
  접미사(`.DLT` vs `-dlt`) 오검, `InventoryConcurrencyTest` CI 플레이크 등.
- 두 문서 모두 상단에 "겪은 문제 요약" 표를 두고 실제 버그/장애(설계 결정이 아닌 것)만
  추려뒀다 — 전체 맥락은 각 절 본문에 그대로 남아 있다.

## 배운 것

- **로컬 DB 트랜잭션의 원자성과 "그 사실이 다른 서비스에 제때 전달됨"은 다른 문제다.**
  2.15에서 "재고 예약+확정이 한 트랜잭션이니 커밋 안 된 상태로 유실될 수 없다 → 재고
  복구가 필요 없다"고 처음에 판단했는데 틀렸다. Transactional Outbox의 폴링 주기만큼
  로컬 커밋과 실제 이벤트 발행 사이에 진짜 시간차가 있고, Saga 타임아웃 스케줄러는
  그 창을 볼 방법이 없다 — "커밋됐다"와 "다른 서비스가 안다"를 같은 시점으로 착각하면
  안 된다는 걸 실제 버그로 겪었다(`05-saga-orchestration.md` §5).
- **Spring Boot 4의 모듈 분리(자동구성이 기능별 jar로 쪼개짐)가 반복적으로 "조용한 실패"를
  만든다.** Jackson 3 groupId 변경(§3), `@EntityScan` 패키지 이동(§5), notification-service의
  `spring-boot-kafka` 누락(§2, 05 문서)까지 — 전부 컴파일은 되고 컨텍스트도 뜨는데 특정
  기능만 조용히 안 도는 패턴이었다. Boot 4로 옮긴 이후 이 계열의 문제를 도합 3번 겪었다.
- **"드묾"은 "불가능"이 아니다.** 컨슈머 멱등성(2.9)에서 "같은 파티션은 보통 한 인스턴스가
  순차 처리하니 동시 중복은 드물 것"이라고 판단해 `existsById`+`save()` 방식을 먼저 썼는데,
  CodeRabbit 리뷰에서 리밸런싱/재시도 타이밍에 따라 실제로 경쟁이 가능하다는 지적을 받고
  DB 네이티브 원자적 INSERT로 바꿨다 — "이론상 가능하면 막는다"는 원칙을 다시 확인했다.
- **CI가 아니면 못 잡는 버그가 이 단계에서도 계속 나왔다.** `@EnableJpaRepositories` 스캔
  범위, `TestTypeExcludeFilter`, notification-service Kafka 미배선 — 셋 다 로컬
  `compileJava`/`compileTestJava`로는 절대 안 잡히고 `@SpringBootTest` 컨텍스트 로딩이
  유일한 방어선이었다. 1단계 교훈("CI가 없으면 안 보이는 문제가 있다")이 서비스가 늘어난
  2단계에서 오히려 더 자주 반복됐다.

## 다음 단계로 넘기는 숙제

- **2-D(2.17~2.20) 자체가 아직 시작 전이다** — 장애 주입 테스트(2.17), 중복 이벤트 강제
  주입(2.18), Testcontainers 기반 Saga 통합 테스트(2.19), 1단계 대비 성능 비교(2.20)
  네 태스크 모두 남아 있다. 위 "완료 기준 달성 현황"의 ⏳ 3개는 이 태스크들이 끝나야
  ✅로 바뀐다.
- **PAYMENT/UNKNOWN 재조회 + `cancelForOrder()` TOCTOU** — 이슈
  [#72](https://github.com/JoJimi/payment-lab/issues/72)로 이월. 로드맵 3.4(실제 PG 상태
  재조회)가 선행돼야 제대로 닫힌다.
- **Saga 상태 조회 API 필요성 재확인** — 로드맵 완료 기준에는 있지만 2-A~2-C 태스크
  목록에는 없었다. 2-D 착수 전에 이 API가 실제로 필요한지(검증 테스트가 필요로 하는지),
  필요하다면 어느 태스크에 붙일지 정해야 한다.
- **2.20 실측은 로컬 Docker 환경 필요** — 이 세션(원격, Docker 없음)에서는 실행할 수
  없다. `scripts/measure-baseline.sh`를 참고해 준비해둔 2단계용 성능 비교 스크립트를
  로컬에서 직접 돌려야 한다(아래 참고).
- **벤치마크 스크립트/문서 9종이 여전히 stale 상태** — `04-msa-split.md` §2에서 2-B 완료
  후 재작성하기로 미뤄둔 것이 아직 그대로다. 2.20과 자연스럽게 묶어서 이번에 함께
  처리하는 게 맞다.
