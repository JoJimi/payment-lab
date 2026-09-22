# 05. Saga 오케스트레이션 (2-C)

2단계 2-C(Saga 오케스트레이션, 이슈 #62~)에서 겪은 문제와 설계 결정을 기록한다.
2-A/2-B(MSA 분리, Kafka 기반)는 [04-msa-split.md](04-msa-split.md) 참고.

### 1. `saga_instance`/`saga_step` 테이블 설계 (2.11)

**`sagaId`를 `orderId`와 별도 UUID로 둔 이유**: 지금은 주문 1건당 Saga가 정확히 1개뿐이라
`order_id`를 그대로 PK로 써도 동작은 한다. 하지만 그러면 나중에(5단계 이후 등) 재시도
Saga가 필요해질 때 식별자 스키마 자체를 바꿔야 한다 — PK 타입을 바꾸는 마이그레이션은
데이터가 쌓인 뒤에는 비용이 크다. 대신 `sagaId`는 애플리케이션에서 `UUID.randomUUID()`로
발급하고(`common-event`의 `eventId`와 같은 패턴), "주문 1건당 Saga 1개"라는 **현재의**
불변식은 `order_id` 컬럼에 유니크 인덱스를 걸어 DB 레벨에서 강제한다. 나중에 이 불변식이
깨져야 할 이유가 생기면 인덱스만 지우면 되고, 식별자 스키마는 안 건드려도 된다.

**`SagaStatus`가 4개 값인데 `COMPENSATING`에서 두 갈래로 갈라지는 이유**: 처음엔
`COMPENSATING`의 종착점이 자명하게 하나(보상 완료 = 끝)라고 생각했다. 그런데 로드맵
2.16(DLQ 구성)이 존재한다는 것 자체가 "보상도 실패할 수 있고, 그때는 자동으로 해소가
안 된다"는 뜻이다. 그래서 `COMPENSATING → COMPLETED`(보상이 끝까지 성공 — 정상 흐름이
끝까지 간 것과 Saga 입장에서는 동급으로 "의도대로 마무리됨")와 `COMPENSATING → FAILED`
(보상 자체가 막힘 — DLQ 재처리 대상)를 분리했다. `STARTED`에서 바로 `FAILED`로 가는
전이는 없다 — 정상 흐름 중 실패는 항상 먼저 `COMPENSATING`을 거친다(실패를 그냥
내버려두면 안 되고, 반드시 보상 시도를 해야 한다는 걸 상태 머신으로 강제한다).

**`SagaStepStatus.FAILED`가 `COMPENSATED`로 못 가는 이유**: 보상은 "이미 벌어진
부수효과를 되돌리는 것"이다. 결제가 PG에서 거절됐다면(`FAILED`) 애초에 승인된 적이
없으니 취소할 결제 자체가 없다 — 그 스텝은 그냥 실패로 끝난 기록이고, Saga 레벨의
보상은 *다른* 스텝(이미 `SUCCESS`한 스텝)에 대해서만 일어난다. `SagaStep.compensate()`가
`SUCCESS`에서만 허용되는 이유가 이것이다.

**Order/Payment의 기존 `canTransitionTo(target)` 패턴을 그대로 재사용**: 1단계에서
`OrderStatus`/`PaymentStatus`에 이미 쓰던 패턴(`docs/domain/state-transitions.md`)을
그대로 가져왔다 — 상태 변경은 항상 엔티티 메서드(`sagaInstance.complete()` 등) 안에서만
일어나고, `InvalidStateTransitionException`(1단계부터 있던 공통 예외, `common.exception`
패키지)으로 잘못된 전이를 막는다. 새 예외 타입을 만들지 않고 기존 것을 재사용했다 —
"허용되지 않는 도메인 상태 전이"라는 의미가 정확히 같고, Saga 전용으로 분리할 이유가
없었다.

**2.11 범위를 테이블+상태 전이로만 한정한 이유**: 로드맵이 2.11(테이블+전이 로직)과
2.12(정상 흐름 완성: 실제 Kafka 이벤트 리스너로 이 상태 머신을 움직이는 것)를 명시적으로
나눠뒀다. 이번 PR은 `SagaInstance`/`SagaStep` 엔티티와 리포지토리, 그리고 상태 전이
규칙 자체가 맞는지만 검증한다 — `OrderService.createOrder()`는 아직 손대지 않았다
(여전히 2.1/2.3에서 남긴 `NOT_IMPLEMENTED`를 던진다). 실제로 주문 생성이 Saga를
시작시키고 Kafka 이벤트로 전진하는 배선은 2.12의 몫이다.

**`saga_step`이 다른 서비스 DB를 참조하지 않는 진짜 FK를 쓰는 이유**: `outbox`/
`processed_event`와 달리 `saga_step.saga_id → saga_instance.saga_id`는 같은
order-service DB 안의 참조다. 2.2에서 서비스 간 소프트 참조(FK 없음)를 쓴 이유는
DB가 물리적으로 분리돼 있어서였지 FK 자체를 피하려던 게 아니다 — 같은 DB 안에서는
참조 무결성을 DB에 맡기지 않을 이유가 없다.

**로컬 검증 한계**: 이 원격 환경엔 Docker가 없어 `SagaRepositoryIntegrationTest`
(Testcontainers Postgres)는 로컬에서 못 돌렸다 — `Could not find a valid Docker
environment` 예외로 실패하는 것까지 확인했고(로직 문제 아님), 실제 실행은 CI에 맡긴다.
반면 상태 전이 자체를 검증하는 `SagaStatusTest`/`SagaStepStatusTest`/`SagaInstanceTest`/
`SagaStepTest`는 순수 단위 테스트(Spring 컨텍스트·Docker 불필요)라 로컬에서 전부
실행하고 통과를 확인했다(22건).
