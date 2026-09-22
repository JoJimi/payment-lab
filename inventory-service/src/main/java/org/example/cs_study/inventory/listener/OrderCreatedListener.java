package org.example.cs_study.inventory.listener;

import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.OrderCreatedPayload;
import org.example.cs_study.inventory.domain.OrderLineItem;
import org.example.cs_study.inventory.repository.OrderLineItemRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code order.created}를 구독해 {@link OrderLineItem} 로컬 읽기 모델을 채운다(2.12,
 * {@link OrderLineItem} Javadoc 참고). {@code payment.completed}가 재고를 예약할 때
 * "이 주문이 무엇을, 몇 개 샀는지"를 여기서 미리 적어둔 값으로 조회한다.
 */
@Component
public class OrderCreatedListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final OrderLineItemRepository orderLineItemRepository;

    public OrderCreatedListener(
            InboxService inboxService, ObjectMapper objectMapper, OrderLineItemRepository orderLineItemRepository) {
        this.inboxService = inboxService;
        this.objectMapper = objectMapper;
        this.orderLineItemRepository = orderLineItemRepository;
    }

    @KafkaListener(topics = "order.created")
    public void onMessage(String message) {
        EventEnvelope<OrderCreatedPayload> envelope = EventEnvelopeReader.read(objectMapper, message, OrderCreatedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> handle(envelope.payload()));
        }
    }

    private void handle(OrderCreatedPayload payload) {
        orderLineItemRepository.save(new OrderLineItem(payload.orderId(), payload.productId(), payload.quantity()));
    }
}
