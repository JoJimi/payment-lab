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

    /**
     * 로드맵 2.18 — {@link PaymentRequestedListener}는 이 프로젝트에서 유일하게
     * {@code InboxService}가 아니라 {@code @Idempotent} AOP로 멱등성을 보장하는 리스너다
     * (Mock PG 호출을 트랜잭션 밖에 둬야 하는 원칙 때문, 클래스 Javadoc). 다른 9개 리스너와
     * 메커니즘 자체가 다르므로 별도로 검증한다 — order-service가 같은 {@code idempotencyKey}를
     * 실은 완전히 같은 메시지(같은 eventId)를 Kafka가 재전달해도 결제가 정확히 한 번만
     * 생성되는지 확인한다. {@code payments.idempotency_key}엔 DB 유니크 제약도 걸려 있어
     * (V2 마이그레이션) @Idempotent가 뚫려도 DB가 마지막 방어선이 되지만, 이 테스트가
     * 실제로 증명하려는 건 정상 경로(AOP 레벨에서 막힘)다.
     */
    @Test
    void payment_requested가_같은_eventId로_두_번_전달돼도_결제는_정확히_한_번만_생성된다() {
        Long orderId = 556L;
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequestedPayload payload =
                new PaymentRequestedPayload(orderId, new BigDecimal("3500.0000"), "KRW", idempotencyKey);
        String json = objectMapper.writeValueAsString(EventEnvelopeFactory.create(EventType.PAYMENT_REQUESTED, payload));

        long before = paymentRepository.count();

        // Kafka at-least-once 재전달 시뮬레이션 — 완전히 같은 메시지를 그대로 두 번 보낸다.
        kafkaTemplate.send("payment.requested", orderId.toString(), json);
        kafkaTemplate.send("payment.requested", orderId.toString(), json);

        awaitApprovedPayment(idempotencyKey);
        assertThat(paymentRepository.count()).isEqualTo(before + 1);
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
