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

### 2. Saga 정상 흐름 배선 (2.12)

**가격을 클라이언트가 직접 보내는 이유**: `OrderService.createOrder`가 2.1/2.3에서
`ProductPriceLookup`(동기 가격 조회) 제거 이후 계속 미구현 상태였다(04-msa-split.md #1
"가격 정보가 없으면 totalAmount를 계산할 방법이 없다"). 이벤트 왕복으로 비동기 가격
조회를 만드는 것도 고려했지만, `event-catalog.md`(2.6)에 그런 토픽이 없고 이번 태스크의
목표(Saga 흐름 자체)에 비해 과하다. 그래서 `CreateOrderRequest`에 `unitPrice`/`currency`를
추가해 클라이언트가 직접 보내는 쪽을 택했다 — Payment Service가 재고를 모르듯(로드맵 부록
G-2) Order Service도 상품 가격의 진실 공급원이 아니라는 원칙과도 맞는다. 가격 위변조 방어는
범위 밖으로 명시적으로 남겼다(2-D 이후 재검토 대상).

**`payment-service`의 `payment.requested` 리스너만 `InboxService`를 안 쓴다**: 이 서비스의
다른 모든 리스너(order/inventory/notification)는 `InboxService.processIfNew`로 감싸는데,
`PaymentRequestedListener`만 다르다. `InboxService`는 "중복 확인 → 비즈니스 로직 → 기록"을
자기 트랜잭션 하나로 묶는 게 핵심인데, `PaymentService`는 정확히 그 반대를 강제한다 — Mock
PG 호출이 `@Transactional` 밖에서 일어나야 부하 상황에 커넥션 풀이 고갈되지 않는다(1단계
설계 원칙, `PaymentService.requestPayment` Javadoc). Inbox로 감싸면 PG 호출이 트랜잭션
안에 갇혀 그 원칙이 깨진다. 대신 `idempotencyKey`가 order-service에서 한 번만 발급되므로
`@Idempotent` AOP(1단계, 부록 A-1)가 Kafka 재전달을 그대로 흡수한다 — 이 메커니즘이 애초에
"외부 I/O가 낀 긴 흐름"을 위해 만들어졌으니 이중 보호가 필요 없다.

**inventory-service가 `order.created`도 구독하는 이유**: `payment.completed`에는
`productId`/`quantity`가 없다(Payment Service는 재고를 모른다는 경계, 부록 G-2) — 그래서
inventory-service는 `order.created`를 먼저 구독해 로컬 읽기 모델(`order_line_item`,
`OrderLineItem.java`)에 "이 주문이 뭘, 몇 개 샀는지" 캐시해뒀다가, `payment.completed`가
오면 그 캐시로 재고를 예약한다. 두 토픽이 서로 다른 파티션 세트라(Kafka는 같은 토픽
안에서만 순서를 보장한다) `order.created`가 아직 처리되기 전에 `payment.completed`가
먼저 도착할 이론적 가능성이 있다 — 그럴 땐 예외를 던져 커밋하지 않고, Kafka 재전달로
다음 시도에서 풀리게 뒀다(체계적인 지연 재시도/DLQ는 2.16의 몫).

**재고 예약과 확정을 같은 트랜잭션에서 바로 잇는 이유**: 부록 A-4의 2단계 모델(예약→확정)은
원래 "Saga가 진행 중인 동안은 예약 상태로 두고, 성공이 확실해지면 확정한다"는 그림이다.
2.12(정상 흐름)에서는 재고 예약 다음 단계가 알림뿐이고, 로드맵 방침상 알림 실패는 보상하지
않는다(2.13) — 즉 재고 예약이 성공한 순간 이 Saga는 사실상 성공이 확정된 것과 같다. 그래서
`reserve()`와 `confirm()`을 지금 당장은 같은 트랜잭션에서 잇달아 부른다. 이 타이밍이
의미를 가지려면(예약만 해두고 확정을 늦춰야 하는 시나리오) 보상이 재고 단계까지 미치는
경우가 있어야 하는데, 2.13에서 그 경계를 다시 볼 때 필요하면 갈라놓는다.

**`saga_step.request_payload`를 못 채우는 경우가 있다**: `InventoryReservedListener`/
`PaymentCompletedListener`(order-service)는 사실 inventory-service에 아무 커맨드도
보내지 않는다(위 항목 참고) — 그래서 INVENTORY 스텝은 order-service 입장에서 "요청"이
없다. `inventory.reserved` 수신이 곧 이 스텝의 존재를 아는 첫 순간이라, PENDING을 거치지
않고 바로 성공으로 기록한다(`InventoryReservedListener` 코드 주석 참고).

**Notification.requested까지 갔지만 아직 `SagaInstance.complete()`는 안 부른다**: 2.12는
"정상 흐름 완성"이지 "Saga 종료 처리"가 아니다 — 알림 발행 이후 Saga를 언제 `COMPLETED`로
확정할지는 보상 경계 설계(2.13)와 묶어서 결정하는 게 맞다고 판단해 일부러 미뤘다
(`InventoryReservedListener` Javadoc).

**로컬에서 실제로 검증한 것**: `OrderServiceIntegrationTest`(Testcontainers Postgres만,
Outbox 행 적재까지 확인), `SagaListenersIntegrationTest`(order-service/inventory-service
각각, `@EmbeddedKafka` + Testcontainers Postgres로 리스너 두 개가 실제로 이어지는지),
`PaymentRequestedListenerTest`(payment-service, Mock PG 인프로세스 기동 포함),
`NotificationRequestedListenerTest`(notification-service, 이 서비스의 첫 통합 테스트)
전부 Docker가 없어 로컬에서는 못 돌렸다 — `Could not find a valid Docker environment`
예외로 실패하는 것까지 확인했고(컴파일은 전부 통과), 실제 실행은 CI에 맡긴다. 반면
`InventoryTest`(`reserve`/`confirm`)와 `EventEnvelopeReaderTest`(common-event, 새로 추가한
디코딩 헬퍼의 왕복 직렬화 검증)는 순수 단위 테스트라 로컬에서 전부 통과를 확인했다.
