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

## 다음 단계

- **2.8**: `outbox` 테이블(2.2에서 스켈레톤만 만들어둠)에 이 페이로드들을 넣는
  `OutboxService`와 폴링 릴레이를 구현합니다.
- **2.9**: 각 컨슈머 서비스에 `processed_event(event_id)` 테이블을 추가해 중복 소비를
  막습니다.
