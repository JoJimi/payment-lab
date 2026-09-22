package org.example.cs_study.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
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

        publish(orderId, "INSUFFICIENT_FUNDS");

        // "아무 일도 안 일어남"은 기다릴 조건이 없다 — 리스너가 처리할 시간을 준 뒤 상태가
        // 그대로인지 확인한다(PaymentService#cancelForOrder가 APPROVED만 걸러 처리하므로,
        // 여기서 실제로 바뀌면 그 필터링이 깨졌다는 뜻이다).
        sleep(Duration.ofSeconds(2));
        Payment unchanged = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void publish(Long orderId, String reason) {
        OrderCancelledPayload payload = new OrderCancelledPayload(orderId, reason);
        String json = objectMapper.writeValueAsString(EventEnvelopeFactory.create(EventType.ORDER_CANCELLED, payload));
        kafkaTemplate.send("order.cancelled", orderId.toString(), json);
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
