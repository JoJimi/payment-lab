# 이벤트 카탈로그

2단계(2-B, 이슈 #51)에서 정의한 Kafka 토픽·이벤트 스키마 명세입니다. 실제 발행/구독
연결은 2.7(이벤트 봉투)~2.8(Transactional Outbox)에서 구현합니다. 여기서는 "무엇을,
어떤 모양으로, 어디로 보낼지"만 확정합니다.

## 공통 규칙

| 항목 | 값 | 근거 |
|---|---|---|
| 파티션 키 | `orderId`(문자열로 직렬화) | 같은 주문의 이벤트는 발행 순서대로 같은 파티션에 들어가 순서가 보장됨(로드맵 2단계 선행 결정) |
| 파티션 수 | 토픽당 3 | 로컬 단일 브로커(2.5)라 파티셔닝 자체를 실습하는 게 목적. 프로덕션 처리량 설계가 아니므로 낮게 유지(부록 B 리소스 예산) |
| 복제 계수 | 1 | 브로커가 1대뿐(2.5) — 복제는 5단계 이후 멀티 브로커 전환 시 재검토 |
| 보존 기간 | 7일(Kafka 기본값, `retention.ms` 미지정) | 로컬 학습 환경에서 디스크를 아끼면서도 DLQ 재처리(2.16)·중복 이벤트 재현(2.18) 실습에 필요한 최소 기간 |
| 직렬화 | JSON, `EventEnvelope<T>`로 감쌈(2.1에서 스켈레톤 정의) | Avro는 학습 부담이 커서 후순위(로드맵 2단계 선행 결정) |
| 필드 순서 | Jackson 3(Boot 4)는 알파벳순 정렬 | 순서가 의미 있는 필드가 생기면 `@JsonPropertyOrder` 명시(`EventEnvelope` Javadoc 참고) |

`EventEnvelope`(`common-event/.../event/EventEnvelope.java`)의 공통 필드:

```java
public record EventEnvelope<T>(
        String eventId,      // UUID
        String eventType,    // 아래 토픽명과 1:1 대응 (예: "order.created")
        int version,         // 페이로드 스키마 버전, 1부터 시작
        Instant occurredAt,
        String traceId,      // MDC가 컨슈머 스레드에서 끊기므로 봉투에 실어 나름
        T payload) {
}
```

## 토픽 목록

| 토픽 | 발행 서비스 | 구독 서비스 | 설명 |
|---|---|---|---|
| `order.created` | order-service | payment-service(Saga 진행) | 주문 생성 직후 |
| `payment.requested` | order-service | payment-service | 오케스트레이터가 결제 실행을 지시하는 커맨드성 이벤트 |
| `payment.completed` | payment-service | order-service, inventory-service | 결제 승인 완료 |
| `payment.failed` | payment-service | order-service | 결제 실패(잔액 부족, PG 오류 등) |
| `inventory.reserved` | inventory-service | order-service | 재고 예약 성공 |
| `inventory.failed` | inventory-service | order-service | 재고 부족으로 예약 실패 |
| `order.cancelled` | order-service | payment-service, inventory-service, notification-service | 보상 트랜잭션 개시(부록 A-4) |
| `notification.requested` | order-service | notification-service | Saga 종료(완료 또는 취소) 시 알림 발송 지시 |

Saga 오케스트레이션(누가 언제 무엇을 트리거하는지)의 상세 상태 전이는 2-C(2.11~2.16)의
`saga-flow.md`에서 다룹니다. 여기서는 각 이벤트가 "존재한다"는 것과 그 페이로드만 고정합니다.

## 페이로드 스키마

모든 금액 필드는 `BigDecimal`입니다(double/float 금지 — `.semgrep/money-no-floating-point.yml`).

### `order.created`
```java
public record OrderCreatedPayload(
        Long orderId,
        Long productId,
        Integer quantity,
        BigDecimal totalAmount,
        String currency) {
}
```

### `payment.requested`
```java
public record PaymentRequestedPayload(
        Long orderId,
        BigDecimal amount,
        String currency,
        String idempotencyKey) {
}
```
`idempotencyKey`는 1단계 멱등성 인프라(부록 A-1)를 그대로 재사용합니다 — Saga 재시도로
같은 커맨드가 두 번 발행돼도 `payment-service`가 중복 결제를 만들지 않도록.

### `payment.completed`
```java
public record PaymentCompletedPayload(
        Long orderId,
        Long paymentId,
        String pgTransactionId,
        BigDecimal amount,
        String currency,
        Instant approvedAt) {
}
```

### `payment.failed`
```java
public record PaymentFailedPayload(
        Long orderId,
        Long paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
```

### `inventory.reserved`
```java
public record InventoryReservedPayload(
        Long orderId,
        Long productId,
        Integer quantity) {
}
```

### `inventory.failed`
```java
public record InventoryFailedPayload(
        Long orderId,
        Long productId,
        Integer quantity,
        String reason) {
}
```

### `order.cancelled`
```java
public record OrderCancelledPayload(
        Long orderId,
        String reason) {
}
```

### `notification.requested`
```java
public record NotificationRequestedPayload(
        Long orderId,
        NotificationType type,   // ORDER_COMPLETED / ORDER_CANCELLED
        String message) {
}
```

## 구현 위치 (2.7)

위 레코드들은 `common-event/src/main/java/org/example/cs_study/event/payload/`에 실제로
정의돼 있습니다. 토픽명은 `EventType` enum으로 고정하고(문자열 리터럴 중복 방지),
`EventEnvelopeFactory.create(EventType, payload)`가 `eventId`/`occurredAt`을 채우고
발행 시점의 MDC에서 `traceId`를 읽어 봉투에 싣습니다. 컨슈머 쪽에서는
`TraceContext.restore(traceId)`(try-with-resources)로 같은 traceId를 MDC에 복원합니다.

## 구현 위치 (2.8)

`common-outbox` 모듈(`OutboxService`/`OutboxRelay`)이 `outbox` 테이블(2.2에서 스켈레톤만
만들어둠)에 이 페이로드들을 넣고 폴링해서 Kafka로 발행합니다. `OutboxService.save()`가
호출자의 트랜잭션에 그대로 올라타 비즈니스 저장과 이벤트 적재를 원자적으로 묶고,
`OutboxRelay`가 유일하게 `KafkaTemplate.send()`를 호출하는 지점입니다(2.10의 Semgrep 룰이
이 클래스만 예외로 허용할 예정). 상세 설계 근거는
[troubleshooting/04-msa-split.md §13](../troubleshooting/04-msa-split.md)에 있습니다.

## 구현 위치 (2.9)

`common-inbox` 모듈(`InboxService`/`ProcessedEvent`)이 order/payment/inventory 각 서비스의
`processed_event` 테이블(event_id를 PK로 직접 사용)에 처리 기록을 남깁니다.
`InboxService.processIfNew(eventId, 비즈니스로직)`이 원자적 선점(`INSERT ... ON CONFLICT
DO NOTHING`) → 처리를 한 트랜잭션으로 묶습니다(`OutboxService`와 달리 스스로 트랜잭션을
엽니다 — Kafka 컨슈머 콜백은 애초에 Spring이 트랜잭션을 열어주지 않는 진입점이라서).
notification-service는 아직 영속 대상이 없어(2.2) 이번에도 배선하지 않았습니다 — 실제
알림 로직을 구현하는 시점(2-C/2-D)에 첫 DB 연결과 함께 다룹니다.

## 구현 위치 (2.10)

`.semgrep/outbox-required.yml`의 `no-direct-kafka-send` 룰이 `OutboxRelay`
(`common-outbox/.../OutboxRelay.java`) 밖에서 `KafkaTemplate.send()`를 직접 호출하면
CI를 막습니다(`metavariable-type`으로 리시버가 `KafkaTemplate`인 호출만 잡고, 경로
예외로 `OutboxRelay.java`만 허용). 상세 설계 근거는
[troubleshooting/04-msa-split.md §15](../troubleshooting/04-msa-split.md)에 있습니다.

이것으로 2-B(Kafka 기반, 이슈 #51)가 끝났습니다. 2-C(Saga 오케스트레이션, 2.11~2.16)에서
실제로 각 토픽을 발행/구독하는 로직을 붙입니다.

## 구현 위치 (2.11)

`order-service`에 `SagaInstance`/`SagaStep` 엔티티와 상태 전이 로직만 추가했습니다 — 아직
어떤 리스너도 없고 `OrderService.createOrder()`도 손대지 않았습니다. 실제로 이 표의
토픽들을 발행/구독하는 배선은 2.12부터입니다.

## 구현 위치 (2.12)

이 표의 8개 토픽이 전부 실제 `@KafkaListener`/`OutboxService.save()` 호출로 연결됐습니다 —
이 프로젝트에서 처음으로 실제 Kafka 이벤트가 끝에서 끝까지(주문 생성 → 알림 발행) 흐릅니다.

| 토픽 | 발행 지점 | 구독 지점 |
|---|---|---|
| `order.created` | `OrderService.createOrder()` | `inventory-service`의 `OrderCreatedListener`(로컬 읽기 모델 적재) |
| `payment.requested` | `OrderService.createOrder()`(같은 트랜잭션) | `payment-service`의 `PaymentRequestedListener` |
| `payment.completed` | `PaymentService.applyResult()` | `order-service`의 `PaymentCompletedListener`, `inventory-service`의 `PaymentCompletedListener` |
| `payment.failed` | `PaymentService.applyResult()` | (2.13에서 구독 — 보상 트랜잭션) |
| `inventory.reserved` | `inventory-service`의 `PaymentCompletedListener` | `order-service`의 `InventoryReservedListener` |
| `inventory.failed` | `inventory-service`의 `PaymentCompletedListener`(재고 부족 시) | (2.13에서 구독) |
| `order.cancelled` | (2.13에서 발행 — 보상 개시) | (2.13에서 구독) |
| `notification.requested` | `order-service`의 `InventoryReservedListener` | `notification-service`의 `NotificationRequestedListener` |

두 가지가 표의 "발행 서비스/구독 서비스" 열과 살짝 다릅니다 — 실제로 구현하면서 드러난
부분이라 여기 기록합니다.

1. **`payment.completed`를 order-service와 inventory-service가 둘 다 직접 구독합니다.**
   order-service는 재고 예약을 별도로 지시하지 않습니다 — inventory-service가 이 토픽을
   스스로 구독해 반응합니다(오케스트레이션이지만 이 한 지점만 이벤트 기반 반응, 로드맵이
   원래 표에 이미 이렇게 적어뒀던 그대로입니다).
2. **`inventory-service`가 `order.created`도 구독합니다.** `payment.completed` 페이로드에는
   `productId`/`quantity`가 없습니다(Payment Service는 재고를 모른다는 서비스 경계,
   로드맵 부록 G-2) — 그래서 inventory-service가 `order.created`로 "이 주문이 뭘 샀는지"를
   미리 로컬 테이블(`order_line_item`)에 적어뒀다가 `payment.completed`가 오면 꺼내 씁니다.
   원래 이 표는 이 구독을 명시하지 않았지만, 실제 배선 과정에서 필요해져 추가했습니다.

설계 근거(가격을 클라이언트가 보내는 이유, 재고 확정을 예약과 같은 트랜잭션에서 바로
하는 이유, notification-service가 처음 DB를 갖게 된 경위 등)는
[troubleshooting/05-saga-orchestration.md](../troubleshooting/05-saga-orchestration.md)에
있습니다.

## 구현 위치 (2.13)

`payment.failed`/`inventory.failed`/`order.cancelled` 세 토픽이 실제로 연결됐습니다 —
보상 트랜잭션(로드맵 "재고 예약 실패 → 결제 취소 → 주문 취소")이 이제 Kafka 이벤트로
동작합니다. 2.12에서 미뤄뒀던 `SagaInstance.complete()`의 정확한 호출 시점도 이번에 정상
흐름/보상 흐름 양쪽 다 확정했습니다.

| 토픽 | 발행 지점 | 구독 지점 |
|---|---|---|
| `payment.failed` | `PaymentService.applyResult()`(2.12부터 이미 발행 중이었음) | `order-service`의 `PaymentFailedListener`(신규) |
| `inventory.failed` | `inventory-service`의 `PaymentCompletedListener`(2.12부터 이미 발행 중이었음) | `order-service`의 `InventoryFailedListener`(신규) |
| `order.cancelled` | `order-service`의 `SagaCompensationService.finish()`(신규) | `payment-service`의 `OrderCancelledListener`(신규), `inventory-service`의 `OrderCancelledListener`(2.15, 아래 정정 참고) |

**표의 "구독 서비스"와 실제로 다른 점 하나**: 원래 2.6 설계 표는 `order.cancelled`를
inventory-service와 notification-service도 구독하는 것으로 적어뒀지만, 2.13 구현 시점에는
`payment-service`만 구독했습니다.

1. **(2.13 당시엔 맞았지만 2.15에서 뒤집힌 판단) inventory-service는 구독하지 않아도 된다고
   했었습니다.** 근거: 2.12에서 `reserve()`와 `confirm()`을 같은 트랜잭션에서 바로 잇달아
   호출하도록 설계했고, `reserve()`는 재고 부족을 확인하면 `available`/`reserved`를 전혀
   건드리지 않고 예외만 던집니다(`Inventory.reserve` 참고) — `inventory.failed`가
   발행되는 시점엔 이 서비스 DB에 되돌릴 부수효과가 없으니, **`inventory.failed`로 트리거된
   보상**에 한해서는 여전히 맞습니다. 하지만 2.15(Saga 타임아웃)가 이 전제를 깨뜨리는 경로를
   하나 더 만들었습니다 — inventory-service가 로컬 커밋(reserve+confirm)은 이미 끝냈는데
   Transactional Outbox(2.8)의 비동기 발행이 아직 안 끝난 창에서, order-service가
   "응답이 안 온다"는 이유만으로 타임아웃 처리를 해버리는 경우입니다. 이때는 실제로 되돌릴
   재고가 있는데 아무도 모르는 채로 남습니다(CodeRabbit 리뷰, PR #71). 그래서 2.15에서
   `inventory-service`도 `order.cancelled`를 구독하는 `OrderCancelledListener`를
   추가했습니다 — `inventory.failed`로 트리거된 취소는 여전히 되돌릴 게 없어 사실상
   no-op이고, 타임아웃으로 트리거된 취소만 실제로 재고를 되돌립니다. 자세한 내용은
   [troubleshooting/05-saga-orchestration.md](../troubleshooting/05-saga-orchestration.md)의
   해당 절 참고.
2. **notification-service는 구독하지 않습니다.** 정상 흐름(`InventoryReservedListener`)과
   대칭으로, order-service가 보상 마무리 시점에 `notification.requested`(type
   `ORDER_CANCELLED`)를 직접 발행합니다 — 알림 채널을 하나로 유지해 notification-service가
   "완료"와 "취소" 두 가지 트리거를 따로 구분해 구독할 필요가 없게 합니다.

설계 근거(각 스텝을 왜 낙관적으로 기록하는지, `SagaInstance.complete()` 호출 시점을
정상/보상 흐름 어디서 확정했는지 등)는
[troubleshooting/05-saga-orchestration.md](../troubleshooting/05-saga-orchestration.md)에
있습니다.
