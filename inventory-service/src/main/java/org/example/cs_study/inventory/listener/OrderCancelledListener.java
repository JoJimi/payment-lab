package org.example.cs_study.inventory.listener;

import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.OrderCancelledPayload;
import org.example.cs_study.inventory.domain.Inventory;
import org.example.cs_study.inventory.domain.OrderLineItem;
import org.example.cs_study.inventory.repository.InventoryRepository;
import org.example.cs_study.inventory.repository.OrderLineItemRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 타임아웃 경로(2.15)가 노출한 구멍을 메운다 — {@code SagaTimeoutService}의 INVENTORY
 * 분기는 order-service 로컬 상태만 보고 판단하므로, "재고 예약이 실제로 끝났는지"를 모른 채
 * 주문을 취소할 수 있다. Transactional Outbox 패턴(2.8) 특성상 inventory-service의 로컬 커밋
 * (reserve+confirm)과 그 사실을 알리는 {@code inventory.reserved} 발행 사이에는 실제 시간차가
 * 있다(OutboxRelay의 별도 폴링 주기) — 그 창에서 타임아웃이 발동하면, inventory-service는
 * 이미 재고를 확정해버렸는데 아무도 되돌리라고 말해주지 않는 상황이 생긴다(CodeRabbit 리뷰,
 * PR #71). 이 리스너가 그 되돌림을 담당한다.
 *
 * <p>{@code order.cancelled}가 {@code payment.completed}보다 먼저 도착하는 순서 역전은
 * {@link OrderLineItem#isReserved()}/{@link OrderLineItem#isCancelled()} 두 플래그로
 * 처리한다 — 자세한 내용은 그 클래스의 Javadoc과 {@link PaymentCompletedListener} 참고.
 */
@Component
public class OrderCancelledListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final OrderLineItemRepository orderLineItemRepository;
    private final InventoryRepository inventoryRepository;

    public OrderCancelledListener(
            InboxService inboxService,
            ObjectMapper objectMapper,
            OrderLineItemRepository orderLineItemRepository,
            InventoryRepository inventoryRepository) {
        this.inboxService = inboxService;
        this.objectMapper = objectMapper;
        this.orderLineItemRepository = orderLineItemRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @KafkaListener(topics = "order.cancelled")
    public void onMessage(String message) {
        EventEnvelope<OrderCancelledPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, OrderCancelledPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> handle(envelope.payload()));
        }
    }

    private void handle(OrderCancelledPayload payload) {
        OrderLineItem lineItem = orderLineItemRepository
                .findByOrderIdForUpdate(payload.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "주문 라인아이템을 아직 못 찾았습니다(order.created 미처리 가능성) — 재시도 대상: orderId="
                                + payload.orderId()));

        if (lineItem.isCancelled()) {
            return;
        }

        if (lineItem.isReserved()) {
            Inventory inventory = inventoryRepository
                    .findByProductIdForUpdate(lineItem.getProductId())
                    .orElseThrow(() -> new IllegalStateException("재고 정보가 없습니다: productId=" + lineItem.getProductId()));
            inventory.release(lineItem.getQuantity());
            inventoryRepository.save(inventory);
        }

        lineItem.markCancelled();
        orderLineItemRepository.save(lineItem);
    }
}
