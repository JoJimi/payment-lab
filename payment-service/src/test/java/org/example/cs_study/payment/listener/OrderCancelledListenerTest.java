package org.example.cs_study.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.example.cs_study.common.inbox.ProcessedEventRepository;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.OrderCancelledPayload;
import org.example.cs_study.mockpg.MockPgServer;
import org.example.cs_study.payment.domain.Payment;
import org.example.cs_study.payment.domain.PaymentStatus;
import org.example.cs_study.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OrderCancelledListener}가 보상 트랜잭션(2.13)의 실행 지점 역할을 하는지 검증한다 —
 * {@code order.cancelled} 수신 시 APPROVED 결제만 취소하고, 그렇지 않은 결제는 조용히
 * 넘어간다(멱등성).
 */
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = "order.cancelled")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.consumer.group-id=payment-service-test",
            "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class OrderCancelledListenerTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payment_lab_test")
            .withUsername("cs")
            .withPassword("cs123");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static MockPgServer mockPgServer;

    @BeforeAll
    static void startMockPg() {
        mockPgServer = new MockPgServer();
    }

    @AfterAll
    static void stopMockPg() {
        mockPgServer.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        int port = mockPgServer.start(0);
        registry.add("mockpg.base-url", () -> "http://localhost:" + port);
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Test
    void order_cancelled를_받으면_APPROVED_결제를_CANCELLED로_바꾼다() {
        Long orderId = 777L;
        Payment payment = new Payment(orderId, UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        payment.approve("tx-cancel-1");
        payment = paymentRepository.save(payment);

        publish(orderId, "OUT_OF_STOCK");

        awaitPaymentStatus(payment.getId(), PaymentStatus.CANCELLED);
    }

    @Test
    void order_cancelled를_받아도_이미_FAILED인_결제는_건드리지_않는다() {
        Long orderId = 778L;
        Payment payment = new Payment(orderId, UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        payment.fail();
        payment = paymentRepository.save(payment);
        Long paymentId = payment.getId();

        String eventId = publishAndGetEventId(orderId, "INSUFFICIENT_FUNDS");

        // processed_event에 기록됐다는 것 자체가 리스너가 예외 없이 끝까지 처리했다는 증거다
        // — 단순히 "상태가 그대로였다"만 보면 필터링이 걸러낸 것인지 리스너가 처리 중 죽은
        // 것인지 구분이 안 된다(CodeRabbit 리뷰, 2.14).
        awaitEventProcessed(eventId);
        Payment unchanged = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void order_cancelled가_서로_다른_eventId로_중복_발행돼도_안전하다() {
        // 2.14: InboxService는 eventId가 같은 재전달만 막는다 — order-service가 같은
        // orderId에 대해 order.cancelled를 서로 다른 eventId로 두 번 발행하는 상황(예: 버그로
        // 인한 중복 발행)까지 흡수하는지 확인한다. PaymentService#cancelForOrder는 APPROVED만
        // 걸러 취소하므로, 첫 처리 후 결제가 CANCELLED가 되면 두 번째 처리는 자연히 no-op다.
        Long orderId = 779L;
        Payment payment = new Payment(orderId, UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        payment.approve("tx-cancel-2");
        payment = paymentRepository.save(payment);
        Long paymentId = payment.getId();

        publish(orderId, "OUT_OF_STOCK");
        awaitPaymentStatus(paymentId, PaymentStatus.CANCELLED);

        String secondEventId = publishAndGetEventId(orderId, "OUT_OF_STOCK");

        // 두 번째 발행이 예외 없이 처리(processed_event 기록)되고 상태가 CANCELLED로 그대로
        // 유지되는지 확인한다 — Payment.cancel()은 APPROVED에서만 허용되는 전이라, 필터링
        // 없이 다시 불렀다면 InvalidStateTransitionException으로 리스너가 죽었을 것이다.
        awaitEventProcessed(secondEventId);
        Payment stillCancelled = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(stillCancelled.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    /**
     * 로드맵 2.18 — 위 2.14 테스트가 다루는 "서로 다른 eventId"(비즈니스 레벨 중복)와 달리,
     * Kafka at-least-once 재전달로 **같은 eventId**가 그대로 두 번 오는 경우를 검증한다.
     * {@code InboxService}가 원자적으로 막아야 하는 영역이다.
     */
    @Test
    void order_cancelled가_같은_eventId로_두_번_전달돼도_한_번만_처리된다() {
        Long orderId = 780L;
        Payment payment = new Payment(orderId, UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        payment.approve("tx-cancel-3");
        payment = paymentRepository.save(payment);
        Long paymentId = payment.getId();

        OrderCancelledPayload payload = new OrderCancelledPayload(orderId, "OUT_OF_STOCK");
        EventEnvelope<OrderCancelledPayload> envelope = EventEnvelopeFactory.create(EventType.ORDER_CANCELLED, payload);
        String json = objectMapper.writeValueAsString(envelope);

        // 완전히 같은 메시지(같은 eventId)를 그대로 두 번 보낸다.
        kafkaTemplate.send("order.cancelled", orderId.toString(), json);
        kafkaTemplate.send("order.cancelled", orderId.toString(), json);

        awaitEventProcessed(envelope.eventId());
        awaitPaymentStatus(paymentId, PaymentStatus.CANCELLED);
    }

    private void publish(Long orderId, String reason) {
        publishAndGetEventId(orderId, reason);
    }

    /** @return 발행한 이벤트의 eventId — 중복 발행 테스트가 InboxService 처리 완료를 기다릴 때 쓴다. */
    private String publishAndGetEventId(Long orderId, String reason) {
        OrderCancelledPayload payload = new OrderCancelledPayload(orderId, reason);
        EventEnvelope<OrderCancelledPayload> envelope = EventEnvelopeFactory.create(EventType.ORDER_CANCELLED, payload);
        String json = objectMapper.writeValueAsString(envelope);
        kafkaTemplate.send("order.cancelled", orderId.toString(), json);
        return envelope.eventId();
    }

    /** {@code SagaListenersIntegrationTest.awaitEventProcessed}와 같은 이유(2.14). */
    private void awaitEventProcessed(String eventId) {
        awaitTrue(
                () -> processedEventRepository.findById(eventId).isPresent(),
                "eventId=" + eventId + "가 시간 내에 처리되지 않았습니다");
    }

    private void awaitPaymentStatus(Long paymentId, PaymentStatus expected) {
        awaitTrue(() -> paymentRepository
                .findById(paymentId)
                .map(Payment::getStatus)
                .filter(expected::equals)
                .isPresent(), "paymentId=" + paymentId + "가 " + expected + "가 되지 않았습니다");
    }

    private void awaitTrue(java.util.function.BooleanSupplier condition, String failureMessage) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError(failureMessage);
    }
}
