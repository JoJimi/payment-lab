package org.example.cs_study.payment.listener;

import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.PaymentRequestedPayload;
import org.example.cs_study.payment.dto.request.RequestPaymentRequest;
import org.example.cs_study.payment.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 정상 흐름에서 결제를 실제로 트리거하는 지점(2.12) — order-service가 주문을 만들며
 * 같은 트랜잭션에서 Outbox로 발행한 {@code payment.requested} 커맨드를 받아
 * {@link PaymentService#requestPaymentFromSaga}를 부른다.
 *
 * <p><b>일부러 {@code InboxService}로 감싸지 않는다</b> — 이 서비스의 다른 모든 리스너와
 * 다른 선택이라 이유를 남긴다. {@code InboxService.processIfNew}는 "중복 확인 → 비즈니스
 * 로직 → 기록"을 자기 트랜잭션 하나로 묶는데, {@code PaymentService}는 정확히 그 반대
 * 설계를 강제한다 — Mock PG 호출이 트랜잭션 밖에서 일어나야 커넥션 풀이 고갈되지 않는다
 * ({@link PaymentService#requestPaymentFromSaga} Javadoc, 부하 상황 커넥션 풀 고갈 방지).
 * 여기서 Inbox로 감싸면 그 원칙이 깨진다. 대신 {@code idempotencyKey}는 order-service가
 * 주문 생성 시 한 번만 발급해 페이로드에 실어 보내므로, Kafka가 이 메시지를 몇 번을 다시
 * 배달해도 {@code @Idempotent} AOP(1단계, 부록 A-1)가 정확히 같은 키로 막아준다 — 이
 * 메커니즘이 이미 "긴 흐름 중간에 외부 I/O가 끼는" 케이스를 위해 만들어졌으므로, 별도
 * Inbox 보호가 필요 없다.
 */
@Component
public class PaymentRequestedListener {

    private final ObjectMapper objectMapper;
    private final PaymentService paymentService;

    public PaymentRequestedListener(ObjectMapper objectMapper, PaymentService paymentService) {
        this.objectMapper = objectMapper;
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = "payment.requested")
    public void onMessage(String message) {
        EventEnvelope<PaymentRequestedPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, PaymentRequestedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            PaymentRequestedPayload payload = envelope.payload();
            RequestPaymentRequest request = new RequestPaymentRequest(payload.orderId(), payload.amount(), payload.currency());
            paymentService.requestPaymentFromSaga(payload.idempotencyKey(), request);
        }
    }
}
