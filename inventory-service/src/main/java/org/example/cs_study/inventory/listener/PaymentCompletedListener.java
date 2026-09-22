package org.example.cs_study.inventory.listener;

import org.example.cs_study.common.exception.inventory.InsufficientStockException;
import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.common.outbox.OutboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.InventoryFailedPayload;
import org.example.cs_study.event.payload.InventoryReservedPayload;
import org.example.cs_study.event.payload.PaymentCompletedPayload;
import org.example.cs_study.inventory.domain.Inventory;
import org.example.cs_study.inventory.domain.OrderLineItem;
import org.example.cs_study.inventory.repository.InventoryRepository;
import org.example.cs_study.inventory.repository.OrderLineItemRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 정상 흐름의 "재고 예약" 단계(2.12) — order-service의 명시적 커맨드 없이
 * {@code payment.completed}를 직접 구독해 스스로 반응한다(docs/architecture/event-catalog.md
 * 서비스 경계 참고). 예약({@code reserve}, available -= n)과 확정({@code confirm},
 * reserved -= n)을 같은 트랜잭션에서 바로 잇달아 호출한다 — 로드맵 방침상 알림(다음 단계)
 * 실패는 이 예약을 되돌리지 않으므로, 확정을 뒤로 미룰 이유가 없다(부록 A-4, {@link
 * Inventory#confirm} Javadoc).
 *
 * <p><b>{@link OrderLineItem}이 아직 없을 수 있다</b> — {@code order.created}와
 * {@code payment.completed}는 서로 다른 토픽이라 Kafka가 둘 사이의 순서를 보장하지 않는다
 * (파티션 내 순서 보장은 같은 토픽 안에서만 유효). 못 찾으면 예외를 던져 이 메시지를
 * 커밋하지 않는다 — 재전달되어 재시도되고, 그 사이 {@link OrderCreatedListener}가 먼저
 * 처리되면 다음 재시도에서 정상적으로 풀린다. 체계적인 지연 재시도/DLQ는 2.16의 몫이다.
 */
@Component
public class PaymentCompletedListener {

    private final InboxService inboxService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final OrderLineItemRepository orderLineItemRepository;
    private final InventoryRepository inventoryRepository;

    public PaymentCompletedListener(
            InboxService inboxService,
            OutboxService outboxService,
            ObjectMapper objectMapper,
            OrderLineItemRepository orderLineItemRepository,
            InventoryRepository inventoryRepository) {
        this.inboxService = inboxService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.orderLineItemRepository = orderLineItemRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @KafkaListener(topics = "payment.completed")
    public void onMessage(String message) {
        EventEnvelope<PaymentCompletedPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, PaymentCompletedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> handle(envelope.payload()));
        }
    }

    private void handle(PaymentCompletedPayload payload) {
        OrderLineItem lineItem = orderLineItemRepository
                .findByOrderId(payload.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "주문 라인아이템을 아직 못 찾았습니다(order.created 미처리 가능성) — 재시도 대상: orderId="
                                + payload.orderId()));

        // 비관적 락(SELECT ... FOR UPDATE) — 같은 상품을 동시에 예약하는 여러 주문이 경합해도
        // 재고 부족 검사와 차감이 원자적으로 이뤄진다. Saga 트리거 경로라 4종 락 전략(1.11) 중
        // 하나를 고정 선택할 이유가 없어(그건 1단계 벤치마크 목적의 스위치다) 가장 단순하고
        // 확실한 전략을 그대로 쓴다.
        Inventory inventory = inventoryRepository
                .findByProductIdForUpdate(lineItem.getProductId())
                .orElseThrow(() -> new IllegalStateException("재고 정보가 없습니다: productId=" + lineItem.getProductId()));

        try {
            inventory.reserve(lineItem.getQuantity());
            inventory.confirm(lineItem.getQuantity());
            inventoryRepository.save(inventory);

            outboxService.save(
                    EventType.INVENTORY_RESERVED,
                    "Inventory",
                    lineItem.getProductId().toString(),
                    new InventoryReservedPayload(payload.orderId(), lineItem.getProductId(), lineItem.getQuantity()));
        } catch (InsufficientStockException e) {
            outboxService.save(
                    EventType.INVENTORY_FAILED,
                    "Inventory",
                    lineItem.getProductId().toString(),
                    new InventoryFailedPayload(payload.orderId(), lineItem.getProductId(), lineItem.getQuantity(), e.getMessage()));
        }
    }
}
