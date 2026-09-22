package org.example.cs_study.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.PaymentRequestedPayload;
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
 * {@link PaymentRequestedListener}가 실제로 {@code payment.requested} 커맨드를 받아 결제를
 * 승인까지 이어가는지 검증한다(2.12). Mock PG는 설정 없이 기동하면 항상 결정론적으로
 * 승인하므로({@link MockPgServer} Javadoc), 이 흐름은 항상 APPROVED로 끝난다.
 */
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = "payment.requested")
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
class PaymentRequestedListenerTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("payment_lab_test").withUsername("cs").withPassword("cs123");

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
    void payment_requested를_받으면_결제를_승인하고_orderId로_조회할_수_있다() {
        Long orderId = 555L;
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequestedPayload payload =
                new PaymentRequestedPayload(orderId, new BigDecimal("2500.0000"), "KRW", idempotencyKey);
        String json = objectMapper.writeValueAsString(EventEnvelopeFactory.create(EventType.PAYMENT_REQUESTED, payload));

        kafkaTemplate.send("payment.requested", orderId.toString(), json);

        awaitApprovedPayment(idempotencyKey);
    }

    private void awaitApprovedPayment(String idempotencyKey) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            Payment payment = paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (payment != null && payment.getStatus() == PaymentStatus.APPROVED) {
                assertThat(payment.getPgTransactionId()).isNotBlank();
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("idempotencyKey=" + idempotencyKey + " 결제가 시간 내에 APPROVED로 바뀌지 않았습니다");
    }
}
