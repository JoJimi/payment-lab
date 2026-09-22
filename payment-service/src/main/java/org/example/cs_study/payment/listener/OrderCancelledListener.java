package org.example.cs_study.payment.listener;

import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.OrderCancelledPayload;
import org.example.cs_study.payment.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 보상 트랜잭션의 실행 지점(2.13) — order-service가 보상을 개시하며 발행한
 * {@code order.cancelled}를 받아 이 주문의 결제가 승인된 상태면 취소한다.
 *
 * <p>{@link PaymentRequestedListener}와 달리 여기는 {@link InboxService}로 감싼다 — 이
 * 메서드는 Mock PG를 부르지 않는 순수 로컬 상태 전이라 그 리스너가 InboxService를 피한 이유
 * (Mock PG 호출을 트랜잭션 밖에 둬야 하는 원칙과의 충돌)가 여기엔 없다.
 */
@Component
public class OrderCancelledListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final PaymentService paymentService;

    public OrderCancelledListener(InboxService inboxService, ObjectMapper objectMapper, PaymentService paymentService) {
        this.inboxService = inboxService;
        this.objectMapper = objectMapper;
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = "order.cancelled")
    public void onMessage(String message) {
        EventEnvelope<OrderCancelledPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, OrderCancelledPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> paymentService.cancelForOrder(envelope.payload().orderId()));
        }
    }
}
