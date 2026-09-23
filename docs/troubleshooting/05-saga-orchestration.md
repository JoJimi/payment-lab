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

### 3. 보상 트랜잭션 (2.13)

**`SagaCompensationService`로 두 트리거({@code payment.failed}/{@code inventory.failed})의
공통 마무리를 뽑아낸 이유**: 두 리스너 모두 "자기 스텝을 기록 → `beginCompensation()` →
주문 취소 → `order.cancelled` 발행 → 취소 알림 발행 → Saga 완료"의 뒷부분이 완전히
동일하다 — 다른 건 "어떤 스텝이 왜 실패했는가"뿐이다. 그 앞부분(스텝별 기록)은 각 리스너에
남기고, 뒷부분만 공통 서비스로 뽑아 중복을 없앴다.

**`payment.failed`는 아무것도 보상하지 않고 바로 주문을 취소하는 이유**: PAYMENT 스텝은
이 시점에 한 번도 성공한 적이 없다(`SagaStepStatus.FAILED`는 `COMPENSATED`로 못 가는
이유와 같은 논리, 2.11). 되돌릴 부수효과 자체가 없으므로 "보상"은 사실상 주문 취소뿐이다.

**`inventory.failed`가 와도 inventory-service에 아무것도 요청하지 않는 이유**: 2.12에서
`reserve()`와 `confirm()`을 같은 트랜잭션에서 바로 잇달아 부르기로 한 설계 때문에, 재고
부족으로 예약이 실패하면 `available`/`reserved` 어느 쪽도 바뀌지 않는다(`Inventory.reserve`가
확인 후 예외만 던지고 끝) — 그래서 "재고를 해제해달라"는 이벤트 자체가 필요 없다. 이
설계가 나중에 바뀌면(예: 예약과 확정 사이에 시간차를 두는 시나리오가 생기면) 이 가정도
같이 재검토해야 한다.

**`inventory.failed` 처리에서 PAYMENT 스텝을 낙관적으로 `COMPENSATED`로 기록하는 이유**:
order-service는 실제로 결제가 취소됐는지 확인할 방법이 없다 — `order.cancelled`를 던질
뿐, payment-service가 그 결과를 알려주는 이벤트가 카탈로그에 없다. 이는 정상 흐름에서
`inventory.reserved` 수신 즉시 INVENTORY 스텝을 성공으로 기록하는 것과 같은 패턴이다
(2.12) — order-service는 애초에 다른 서비스의 로컬 상태를 직접 확인할 수단이 없고, 그게
바로 이 시스템이 MSA인 이유다. "정말로 취소됐는지" 보장은 `order.cancelled`가 최종적으로
전달된다는 것(Outbox+Kafka at-least-once)과 payment-service 쪽 처리가 멱등하다는 것(아래)
두 가지가 함께 만든다.

**보상 마무리에서 `sagaInstance.currentStep`을 안 건드리는 이유**: 정상 흐름은 각 리스너가
`advanceTo()`로 다음 단계를 명시한다. 보상 흐름은 "다음 단계로 나아가는" 게 아니라
"실패한 지점에서 멈춘 것"이므로, `currentStep`을 그대로 두면 나중에 이 Saga를 들여다볼 때
"어디서 실패해 되돌아갔는지"가 바로 보인다 — `NOTIFICATION`으로 옮기면 오히려 "정상적으로
거기까지 진행했다"는 오해를 준다.

**2.12에서 미뤄뒀던 `SagaInstance.complete()` 호출 시점을 여기서 확정한 이유**: 정상
흐름(알림 발행 직후)과 보상 흐름(취소 알림 발행 직후) 둘 다 "더 이상 결과를 기다릴 이벤트가
없는 시점"이 정확히 같은 자리다 — notification-service가 알림을 실제로 보냈는지 확인하는
이벤트가 카탈로그에 없고, 있어도 Saga가 그걸 기다릴 이유가 없다(알림 실패는 보상 대상이
아니므로 기다렸다 실패해도 할 일이 없다). 그래서 두 경로 모두 "알림 발행 지시를 Outbox에
적재했다"를 Saga 완료의 기준으로 삼았다. `InventoryReservedListener`의 NOTIFICATION 스텝도
이번에 `succeed()`를 실제로 호출하도록 고쳤다 — 2.12에서는 스텝을 만들기만 하고 한 번도
성공으로 전이시키지 않아 영원히 PENDING으로 남는 버그가 있었다.

**`payment-service`의 `OrderCancelledListener`가 `PaymentRequestedListener`와 달리
`InboxService`를 쓰는 이유**: `PaymentRequestedListener`가 Inbox를 피한 이유(Mock PG
호출이 `@Transactional` 밖에 있어야 하는 원칙과의 충돌)가 여기엔 없다 — 결제 취소는
로컬 상태 전이(`Payment.cancel()`)뿐이고 외부 PG를 다시 부르지 않는다. 그래서 다른
정상 흐름 리스너들과 같은 패턴을 그대로 따른다.

**`PaymentService.cancelForOrder()`가 멱등한 이유**: `order_id`로 조회한 뒤 상태가
APPROVED인 것만 걸러 취소한다 — 이미 CANCELLED거나(재전달) FAILED인(애초에 이 보상을
유발한 그 결제 자신) 결제는 그냥 지나친다. `Payment.cancel()`은 APPROVED에서만 허용되는
전이라 필터링 없이 무작정 호출하면 재전달 시 `InvalidStateTransitionException`이 났을
것이다. 별도의 이벤트-ID 기반 중복 방지(Inbox) 없이도 이 메서드 자체가 안전하다 — 2.14
"보상 자체의 멱등성"이 다룰 문제의 한 사례를 여기서 미리 만족한 셈이다.

**로컬에서 실제로 검증한 것**: order-service `SagaListenersIntegrationTest`에 보상 흐름
2개(payment.failed, inventory.failed)를 추가했고, payment-service에
`OrderCancelledListenerTest`(APPROVED 결제 취소 + FAILED 결제 무시 2케이스)를 새로
작성했다 — 전부 `@EmbeddedKafka` + Testcontainers Postgres(+payment-service는 Redis, Mock
PG)가 필요해 이 원격 환경(Docker 없음)에서는 못 돌렸다. 컴파일(`compileJava`
`compileTestJava` 전체 모듈)은 통과를 확인했고, 이번 태스크는 새 도메인 상태 전이를
추가하지 않아(기존 `SagaStatus`/`SagaStepStatus`/`PaymentStatus` 전이 규칙을 그대로 씀)
2.11의 순수 단위 테스트(`SagaStatusTest`/`SagaStepStatusTest`/`SagaInstanceTest`/
`SagaStepTest`)를 로컬에서 재실행해 회귀가 없음을 확인했다.

### 4. 보상 자체의 멱등성 (2.14)

**왜 필요한가 — `InboxService`만으로는 안 막히는 중복이 있다**: `InboxService`는
`eventId`가 같은 재전달(Kafka at-least-once)만 막는다. `payment.failed`/`inventory.failed`가
버그나 재시도 로직으로 서로 다른 `eventId`를 달고 같은 주문에 대해 두 번 발행되면, 두 번째
호출도 "새 이벤트"로 보여 `InboxService`를 그냥 통과한다 — 그 상태에서 이미 `FAILED`/
`COMPENSATED`인 스텝에 `fail()`/`compensate()`를 다시 부르면 `InvalidStateTransitionException`이
난다(PR #63에서 CodeRabbit이 지적했고, "2.14에서 처리하겠다"고 답했던 항목).

**두 겹 방어를 뒀다**:
1. **상태 가드(응용 계층)** — `PaymentFailedListener`/`InventoryFailedListener`가 스텝을
   건드리기 전에 `sagaInstance.getStatus() != SagaStatus.STARTED`면 곧장 반환한다. 이
   Saga가 이미 보상 중이거나 끝났다는 뜻이므로 더 할 일이 없다. 두 트랜잭션이 동시에
   같은 행을 읽어 둘 다 이 가드를 통과하는 진짜 경합까지는 못 막는다(같은 트랜잭션 안의
   읽기-쓰기라 DB 커밋 순서에 달렸다) — 그건 2번이 막는다.
2. **낙관적 락(DB 계층)** — `saga_instance`/`saga_step`에 `version` 컬럼을 추가하고 JPA
   `@Version`으로 관리한다(`Inventory.version`, 1.11과 같은 패턴). 위 경합이 실제로
   일어나면 나중에 커밋하는 트랜잭션이 `OptimisticLockingFailureException`으로 실패한다
   — `InboxService`가 같은 트랜잭션에서 `processed_event` INSERT까지 롤백시키므로, 그
   이벤트는 "처리 안 됨"으로 남아 Kafka가 재전달한다. 다음 시도에서는 이미 갱신된
   상태를 보고 1번 가드가 정상적으로 걸러낸다.

**PR #63에서 CodeRabbit이 지적했던 두 번째 항목(`@Version` 자체)을 왜 그때는 반려하고
지금 추가했나**: 2.11 시점엔 실제 동시 쓰기 주체가 없었다(리스너 자체가 없었으므로).
2.13에서 여러 Kafka 리스너가 같은 `saga_id` 행을 건드리게 된 지금이 "필요해지면 추가한다"던
그 시점이다.

**`payment-service`의 `PaymentService.cancelForOrder()`는 왜 추가 조치가 필요 없었나**:
2.13에서 이미 APPROVED 상태만 걸러 취소하도록 만들어뒀다(`Payment.cancel()`이 APPROVED에서만
허용되는 전이라는 도메인 규칙을 그대로 이용) — 같은 주문에 대해 `order.cancelled`가 몇 번
오든, 처음 한 번만 실제로 상태가 바뀌고 그 다음부터는 필터에 걸려 자연히 no-op다. 별도
가드나 낙관적 락을 추가하지 않았다.

**로컬에서 실제로 검증한 것**: `SagaListenersIntegrationTest`에 `payment.failed`를 서로
다른 `eventId`로 두 번 발행하는 테스트를, `OrderCancelledListenerTest`에 `order.cancelled`를
두 번 발행하는 테스트를 추가했다 — 둘 다 Docker가 필요해 이 원격 환경에서는 못 돌렸다.
컴파일과 2.11의 순수 단위 테스트(도메인 상태 전이 규칙 — `@Version` 추가는 전이 규칙 자체를
바꾸지 않는다) 재실행으로 회귀가 없음을 확인했다.

### 5. Saga 타임아웃 처리 (2.15)

**왜 필요한가 — 실패 이벤트 자체가 안 오는 경우**: 2.13/2.14는 "`payment.failed`/
`inventory.failed`가 온다"는 것을 전제로 한다. 하지만 다운스트림 서비스가 그 이벤트를 아예
못 보내는 상황도 있다 — payment-service가 크래시했거나, Mock PG 응답을 기다리다 프로세스가
죽었거나, Kafka 메시지 자체가 유실되는 등. 이럴 땐 order-service 입장에서 아무 신호도 안
와서 Saga가 `STARTED`에 영원히 멈춘다. `SagaInstance.timeoutAt`(2.11에서 이미 만들어둔
컬럼, "이 시각을 넘겨도 STARTED에 머무르면 회수 대상"이라는 Javadoc)이 정확히 이 상황을
위한 것이었다 — 2.15에서 실제로 회수하는 스케줄러를 붙인다.

**`SagaTimeoutService`가 `PaymentFailedListener`/`InventoryFailedListener`와 로직이
겹치는데 왜 합치지 않았나**: 셋 다 "스텝을 실패/보상 대상으로 표시 → `beginCompensation()`
→ `SagaCompensationService.finish()`"라는 뒷부분은 같지만, 앞부분(어떤 스텝을 왜 실패로
보는지)의 트리거가 다르다 — 리스너는 실제 이벤트 페이로드에서 이유를 읽고, 타임아웃은
"응답이 안 왔다" 자체가 이유다. `currentStep`을 보고 `PAYMENT`/`INVENTORY` 중 어느 스텝이
막혀 있었는지 스스로 판단해야 하는 것도 리스너들과 다르다(리스너는 어떤 스텝 얘기인지
이벤트 토픽 자체가 알려준다). 공통된 뒷부분은 이미 `SagaCompensationService.finish()`로
뽑혀 있으니 그걸 그대로 재사용했다.

**`NOTIFICATION` 단계에서 타임아웃 회수가 시도되면 `FAILED`로 즉시 격리하는 이유**:
`InventoryReservedListener`가 `notification.requested` 발행과 `sagaInstance.complete()`를
같은 트랜잭션에서 묶어두므로(2.13), `currentStep`이 `NOTIFICATION`이면서 `status`가
여전히 `STARTED`인 채로 스케줄러 폴링에 걸릴 창이 이론상 없다. 여기 걸린다는 건 그
전제(같은 트랜잭션 보장) 자체가 깨졌다는 뜻이라, 처음엔 예외를 던져 드러내는 쪽을
택했다. 그런데 예외로 트랜잭션이 롤백되면 `status`가 `STARTED`, `timeoutAt`이 과거인
채로 그대로 남아 다음 폴링(기본 30초)마다 또 같은 Saga가 걸려 같은 ERROR 로그가 무한
반복된다(CodeRabbit 리뷰, PR #71) — "진짜 버그를 드러낸다"는 의도가 "로그를 스팸으로
만들어 정작 중요한 신호를 묻어버린다"는 부작용으로 뒤집힌다. 그래서 ERROR 로그는 한 번만
남기고, Saga 자체는 `beginCompensation()`→`failCompensation()`으로 즉시 `FAILED`(종결
상태)로 격리해 더 이상 폴링 대상에서 빠지게 했다 — 여전히 아무것도 자동으로 보상하지
않고(이 케이스는 애초에 "무슨 일이 있었는지 모르는" 상황이라 섣불리 주문을 취소하면
오히려 위험할 수 있다), 사람이 들여다봐야 한다는 신호만 명확히 남긴다. 이렇게 격리된
`FAILED` Saga를 체계적으로 재처리하는 방법은 2.16(DLQ)의 몫이다.

**타임아웃 값을 상수에서 `@Value` 설정으로 바꾼 이유**: 2.12에서 10분 고정 상수로
남겨뒀던 이유가 "2.15에서 스케줄러가 이 값을 근거로 회수한다"였다 — 실제로 스케줄러를
테스트하려면 10분을 기다릴 수 없으니, `app.saga.timeout-minutes`로 빼서 테스트가 0분(즉시
회수 대상)으로 주입할 수 있게 했다. `OutboxRelay`의 `app.outbox.relay.fixed-delay-ms`
패턴을 그대로 따랐다.

**스케줄러가 Saga 하나의 회수 실패로 전체가 멈추지 않게 한 이유**: `OutboxRelay`가 발행
실패를 이벤트별로 삼키고 넘어가는 것과 같은 이유다 — `SagaTimeoutScheduler.
reclaimTimedOutSagas()`는 `@Transactional`이 아니고, `SagaTimeoutService.reclaim()`을
Saga별로 개별 호출하면서 예외를 잡아 로깅만 한다. 하나가 실패해도(트랜잭션이 롤백돼
`timeoutAt`을 여전히 넘긴 채로 남으므로) 다음 폴링에서 다시 시도되고, 나머지 Saga의
회수를 막지 않는다.

**알려진 한계 — PAYMENT 타임아웃이 실제로는 승인된 결제를 취소해버릴 수 있다**:
`SagaTimeoutService`는 order-service의 로컬 상태(응답이 안 왔다는 사실)만 보고 판단한다 —
payment-service에 "이 결제 실제로 어떻게 됐냐"고 재조회하지 않는다. 그런데 Mock PG가
`TIMEOUT`(결과를 알 수 없음)으로 응답하면 `Payment`는 `UNKNOWN` 상태로 남고 `payment.
completed`도 `payment.failed`도 발행되지 않는다(`PaymentService.applyResult` Javadoc) —
이 경우 실제로는 PG가 승인했을 수도 있는데, order-service는 "응답이 없다"는 것만 보고
주문을 취소해버릴 수 있다. 돈은 나갔는데 주문은 취소된 상태가 되는 것이다.

이건 2.15의 결함이 아니라 `PaymentStatus.UNKNOWN`이 원래부터 "실제 조회로 해소하기
전까지는 아무것도 단정하지 않는다"는 설계이기 때문이다(`PaymentStatus.markUnknown`/
`resolveFromUnknown` Javadoc, "UNKNOWN 상태를 재조회 결과로 확정한다 — 3.4에서 실제 조회
로직 연결 예정"). 2.15는 "실패 이벤트 자체가 안 오는 상황에 대한 마지막 방어선"이지,
UNKNOWN을 실제로 해소하는 재조회 시스템이 아니다 — 그건 로드맵이 이미 3.4로 분리해둔
별도 태스크다(CodeRabbit 리뷰, PR #71 — 이 한계를 지적받아 여기 명시적으로 기록한다).
3.4가 들어오면 `SagaTimeoutService`도 "무작정 실패 처리" 대신 "먼저 실제 상태를 재조회"로
바뀌어야 한다.

**같은 한계의 다른 얼굴 — `cancelForOrder()`의 TOCTOU도 같은 이유로 지금은 닫지 않는다**:
CodeRabbit이 이어서 지적한 부분이다(PR #71) — `order.cancelled`가 도착했을 때 결제가 아직
없거나(`payment.requested` 자체가 지연 중) `PENDING`이면 `PaymentService.cancelForOrder()`는
`APPROVED`인 결제만 취소하므로 조용히 넘어간다. 그 뒤 지연됐던 결제가 뒤늦게 `APPROVED`로
확정되면 `payment.completed`가 발행되지만, 이미 취소된 주문이라 아무도 그 결제를 취소/환불
하지 않는다 — INVENTORY 쪽에서 고친 것과 같은 모양의 레이스다.

INVENTORY와 다르게 이건 바로 고치지 않았다: 제대로 닫으려면 결제 서비스에 "이 주문은
취소됐다"는 의도를 Payment 행의 존재 여부와 무관하게 영속화하고, `requestPaymentFromSaga`/
`applyResult`(Mock PG 호출이 트랜잭션 밖에 있는 삼단 구조, `PaymentService` Javadoc)가 그
의도를 확인해 늦게 승인된 결제를 취소하거나 환불해야 한다 — 새 테이블/마이그레이션과
두 트랜잭션 경계에 걸친 로직이 필요한 별도 작업이다. 게다가 이 마무리 없이 "취소된
주문이면 무조건 실패 처리"만 얹으면, 실제로는 PG가 승인했을 수도 있는 결제를 재조회 없이
단정하는 셈이라 위 PAYMENT/UNKNOWN 한계와 똑같은 문제를 새로 만든다 — 결국 제대로 닫으려면
3.4의 실제 PG 상태 조회가 먼저 필요하다. 그래서 이것도 3.4 범위로 미루고 여기 한계로
기록한다.

**(정정) INVENTORY 타임아웃에서 "커밋된 재고를 복구"할 필요가 없다고 했던 것은 틀렸다**:
처음엔 이렇게 판단했었다 — "inventory-service는 `reserve()`와 `confirm()`을 같은 로컬
트랜잭션에서 잇달아 호출하므로(2.12), 그 트랜잭션이 커밋 안 되면(크래시 등) 재고 변경도
함께 롤백된다. 즉 '재고는 깎였는데 `inventory.reserved` 확인만 못 받은' 상태는 존재할 수
없다." 이 추론은 **"로컬 DB 트랜잭션이 원자적이다"와 "그 사실이 다른 서비스에 제때
전달된다"를 같은 것으로 착각했다** — Transactional Outbox 패턴(2.8)에서는 이 둘이 분리돼
있다. `reserve()`+`confirm()`+Outbox 행 적재는 분명 한 트랜잭션에서 원자적으로 끝나지만,
그 Outbox 행을 실제로 Kafka에 발행하는 건 `OutboxRelay`의 **별도 폴링 주기**다 — 로컬
커밋과 `inventory.reserved` 발행 사이에는 진짜 시간차가 있다. `SagaTimeoutScheduler`는
그 창을 볼 방법이 없다 — order-service 로컬 상태(응답이 안 왔다는 사실)만 보고 판단하기
때문에, inventory-service가 이미 재고를 확정해버렸는데도 "응답이 없으니 실패로 간주"하고
주문을 취소할 수 있다. CodeRabbit이 최초 반박에서 이 착각을 정확히 짚어냈다(PR #71).

**수정**: `OrderLineItem`에 `reserved`/`cancelled` 두 플래그를 추가했다(V5 마이그레이션).
`PaymentCompletedListener`가 예약+확정에 성공하면 `reserved=true`로 표시하고,
새로 추가한 inventory-service의 `OrderCancelledListener`가 `order.cancelled`를 받아
`reserved=true`인 라인아이템만 `Inventory.release()`(신규, `available += n`)로 되돌린다.
`order.cancelled`와 `payment.completed`는 서로 다른 토픽이라 어느 쪽이 먼저 올지 Kafka가
보장하지 않으므로, 취소가 예약보다 먼저 도착하는 순서 역전은 `cancelled` 플래그로 막는다
— `OrderCancelledListener`가 먼저 `cancelled=true`를 남겨두면, 뒤늦게 오는
`PaymentCompletedListener`가 그 라인아이템을 보고 예약 자체를 건너뛴다(예약했다가 바로
되돌릴 이유가 없다).

같은 레이스가 order-service 쪽에도 있었다 — 타임아웃이 먼저 Saga를 COMPLETED로 끝내버린
뒤 뒤늦게 `inventory.reserved`가 도착하면, `InventoryReservedListener`가 이미 끝난 Saga를
다시 전이시키려다 `SagaStatus.canTransitionTo`에 막혀 예외를 던지고 무한 재시도로
이어질 수 있었다. 2.14의 상태 가드와 같은 패턴(`status != STARTED`면 조용히 무시)을
여기도 추가했다.

**로컬에서 실제로 검증한 것**: `SagaTimeoutSchedulerTest`(Postgres만, Kafka 불필요 — 스케줄러는
로컬 DB 상태만 보고 판단한다) 2케이스 — PAYMENT 단계 타임아웃(스텝 자체가 한 번도 응답을
못 받은 경우), INVENTORY 단계 타임아웃(결제는 성공했는데 재고 응답이 안 온 경우, 리스너
없이 그 최종 상태를 테스트가 직접 재현). 둘 다 Docker가 필요해 이 원격 환경에서는 못
돌렸다. 컴파일 전체 모듈 통과와 2.11의 순수 단위 테스트 재실행으로 회귀가 없음을 확인했다
— inventory-service의 release 경로 자체에 대한 통합 테스트는 아직 없다(Kafka가 필요해
이 원격 환경에서 작성/실행이 어렵다는 같은 제약).

### 6. DLQ 구성 + 재처리 (2.16)

**문제**: 지금까지 모든 `@KafkaListener`는 예외를 던지면 Spring Kafka 기본 설정에
기대고 있었다 — 즉시 재시도 9회(간격 0ms) 후 실패하면 그냥 로그만 남기고 조용히
스킵한다. DLQ가 없으니 실패한 메시지의 흔적이 로그 말고는 안 남고, "재시도를 다 써서
포기했다"와 "성공했다"를 운영자가 구분할 방법이 없다. 여러 리스너 Javadoc(예:
`PaymentCompletedListener`)에 이미 "체계적인 지연 재시도/DLQ는 2.16의 몫이다"라고
적어뒀던 부분이다.

**구성**: 새 모듈 `common-kafka`에 `KafkaErrorHandlerConfig` 하나만 둔다 —
`DeadLetterPublishingRecoverer` + `DefaultErrorHandler`(500ms 간격, 최초 시도 포함
총 3회)로 만든 `CommonErrorHandler` 빈이다. 이 타입 빈은 Boot의
`ConcurrentKafkaListenerContainerFactoryConfigurer`가 기본 리스너 컨테이너 팩토리에
자동으로 물려주므로, 소비 서비스(order/payment/inventory/notification)는 이 모듈을
의존성에 추가하기만 하면 되고 기존 `@KafkaListener` 코드는 한 줄도 안 바뀐다.

재시도 간격(500ms, 3회)은 이미 이 코드베이스에 있는 Mock PG 재시도 값
(payment-service `application.yml`의 `resilience4j.retry.instances.mockPg`)과
맞췄다 — 순간적인 이벤트 도착 순서 역전(예: `payment.completed`가 `order.created`보다
먼저 오는 경우, 여러 리스너 Javadoc에 이미 문서화된 레이스)은 보통 이 안에서 풀린다.

**왜 소비 서비스마다 `KafkaTemplate<String, String>` 빈이 있어야 하나**:
`DeadLetterPublishingRecoverer`가 DLT에 발행하려면 프로듀서가 필요하다. order/payment/
inventory-service는 이미 common-outbox의 `OutboxKafkaConfig`가 이 빈을 제공하지만,
notification-service는 순수 컨슈머라(event-catalog.md) 이 빈이 없었다 — 2.16에서
`notification-service`에도 같은 패턴의 `KafkaProducerConfig`를 추가했다(Boot 자동구성
`KafkaTemplate`은 와일드카드 제네릭이라 이 필드 타입과 안 맞는 문제는 여기서도 똑같다).

**DLT 토픽 이름에 관한 함정**: 흔히 알려진 접미사는 `.DLT`지만, spring-kafka 4.1.1의
`DeadLetterPublishingRecoverer` 실제 기본값은 소문자 하이픈 `-dlt`다 —
`common-kafka`의 `KafkaErrorHandlerConfigTest`를 처음 `.DLT`로 가정하고 짰다가
"No records found for topic"으로 실패하면서 직접 확인했다(정정 완료). 즉 `order.created`
DLQ는 `order.created-dlt`다.

**`InboxService`(2.9)와의 관계**: 재시도 도중에는 리스너가 끝까지 성공하지 못했으므로
`processed_event`에 기록되지 않는다. DLT에서 원본 토픽으로 재발행된 메시지는
`eventId`가 그대로라 Inbox가 정상적으로 "새 이벤트"로 처리한다 — 재처리 전용 로직이
따로 필요 없다.

**DLQ 재처리 절차**:
1. `<토픽>-dlt`에 쌓인 메시지를 확인한다(`docker exec payment-lab-kafka
   /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092
   --topic <토픽>-dlt --from-beginning`). 값은 실패한 원본 `EventEnvelope<T>` JSON
   그대로이므로 원인을 알아내는 데 그대로 쓸 수 있다.
2. **원인을 먼저 고친다** — 코드 버그면 배포, 일시적 인프라 장애(DB/Redis 다운 등)면
   해당 인프라를 복구한다. 원인을 안 고치고 재발행하면 같은 재시도(3회)를 또 소진해
   같은 메시지가 DLT에 또 쌓일 뿐이다.
3. **원본 토픽을 구독 중인 서비스를 잠깐 멈춘다**(CodeRabbit 리뷰, PR #74) — 재발행 중에
   컨슈머가 살아있으면 "재발행 → 즉시 재소비 → (원인이 덜 고쳐졌으면) 재실패 → 같은
   DLT에 또 쌓임"이 `scripts/replay-dlq.sh` 한 번 실행(약 5초) 안에서도 일어날 수 있다.
   서비스가 꺼져 있으면 재발행은 원본 토픽에 쌓이기만 하고, 서비스를 다시 켰을 때 한
   번에 정상 소비된다.
4. `scripts/replay-dlq.sh <토픽>`으로 `<토픽>-dlt`의 메시지를 원본 토픽에 그대로
   재발행한다. 키/값만 옮기고(예외 정보가 담긴 헤더는 버려짐 — 원본 컨슈머는 그 헤더를
   보지 않으므로 문제 없다) DLT의 원본 메시지는 지우지 않는다(재발행이 실제로 잘
   처리됐는지 확인할 때까지 감사 로그로 남겨둔다). 이 스크립트는 키/값을 탭·개행으로
   구분되는 텍스트로 다룬다 — 이 프로젝트의 실제 페이로드(키=orderId 숫자 문자열,
   값=한 줄 압축 JSON)는 이 경계에 걸리지 않지만, 일반적인 바이트 그대로 옮기는 도구는
   아니다(의도적 범위 제한, `scripts/replay-dlq.sh` 상단 주석 참고).
5. 정상 처리됐는지 확인한 뒤(각 서비스 로그, 또는 도메인 상태) 멈춰뒀던 서비스를 다시
   켜고, DLT의 원본 메시지를 직접 정리한다(운영에서는 보존 기간 7일이 지나면 자동으로
   사라진다, event-catalog.md 공통 규칙 표 — 2.16을 위해 일부러 이 기간을 맞춰뒀었다).

**로컬에서 실제로 검증한 것**: `common-kafka`의 `KafkaErrorHandlerConfigTest`
(임베디드 브로커, Docker 불필요) — 계속 실패하는 리스너가 재시도 3회(약 1초)를 다
소진하면 원본 레코드가 정확히 `<토픽>-dlt`에 그대로 발행되는 것을 직접 증명한다.
`scripts/replay-dlq.sh`는 로컬 docker-compose Kafka가 있어야 해 이 원격 환경에서는
실행해보지 못했다 — `kafka-console-consumer.sh`/`kafka-console-producer.sh` 파이프라는
잘 알려진 패턴을 그대로 썼다.

### 7. 장애 주입 테스트가 실제 버그를 하나 찾아냈다 — `PaymentCompletedListener`에만 없던 상태 가드 (2.17)

**증상**: 2.17("각 서비스를 하나씩 죽인 상태로 주문 → 복구 후 Saga가 이어지거나 보상되는지")
테스트를 작성하며 "PAYMENT 타임아웃으로 주문이 CANCELLED된 뒤, payment-service가 복구돼
뒤늦게 `payment.completed`가 도착한다"는 시나리오를 실제 `@EmbeddedKafka`로 재현했더니,
`PaymentCompletedListener.handle()`이 `order.markPaid()`에서 `InvalidStateTransitionException`
(`CANCELLED → PAID`는 허용되지 않는 전이)을 던졌다 — 조용한 실패는 아니지만(예외가 나므로),
문서화된 적 없는 실제 실패 경로였다.

**원인**: 같은 성격의 레이스를 이미 다루는 세 리스너(`InventoryReservedListener`,
`PaymentFailedListener`, `InventoryFailedListener`, 각각 2.14/2.15에서 추가)는 전부
"Saga가 이미 `STARTED`를 벗어났으면 조용히 무시"하는 상태 가드를 갖고 있는데,
`PaymentCompletedListener`만 2.12(정상 흐름 최초 구현) 이후 이 가드가 없었다 — 2.15
(Saga 타임아웃)가 나중에 추가되면서 "타임아웃이 먼저 끝낸 뒤 뒤늦은 성공 응답이 온다"는
경로가 새로 생겼는데, 그 경로를 실제로 실행해보는 테스트가 2.17 전까지 없었다.

**해결**: `PaymentCompletedListener.handle()` 맨 앞에서 `SagaInstance`를 먼저 조회해
`status != STARTED`면 곧장 반환하도록 수정(다른 세 리스너와 같은 패턴). 가드를 통과 못하는
이벤트는 예외 없이 조용히 버려진다 — 재시도 3회(2.16) 소진 후 `payment.completed-dlt`에
쌓이던 것과 달리, 이제는 DLT까지 가지 않는다.

**영향 범위**: 이 레이스가 실제로 일어나는 빈도 자체는 낮다(타임아웃 회수와 지연된 PG
응답이 겹쳐야 한다) — 하지만 일어나면 매번 재시도 예산을 태우고 DLT에 수동 조사 대상을
쌓는다는 점에서, 2.16(DLQ)이 막으려던 "잡음"에 정확히 해당한다.

**재발 방지**: `FaultInjectionIntegrationTest`(order-service, 2.17)의
`PAYMENT_서비스가_죽으면_타임아웃으로_보상되고_복구후_뒤늦은_응답은_무시된다()`가 이
가드를 고정한다 — 가드가 없어지면 이 테스트가 예외로 실패하거나 DLT에 레코드가
남아 실패한다.
