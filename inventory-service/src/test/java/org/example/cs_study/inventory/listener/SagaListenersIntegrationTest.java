package org.example.cs_study.inventory.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.OrderCreatedPayload;
import org.example.cs_study.event.payload.PaymentCompletedPayload;
import org.example.cs_study.inventory.domain.Inventory;
import org.example.cs_study.inventory.domain.Product;
import org.example.cs_study.inventory.repository.InventoryRepository;
import org.example.cs_study.inventory.repository.OrderLineItemRepository;
import org.example.cs_study.inventory.repository.ProductRepository;
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
 * inventory-service 쪽 Saga 리스너 2개({@link OrderCreatedListener}, {@link PaymentCompletedListener})가
 * 이어 붙어 "order.created로 라인아이템을 캐시해뒀다가 payment.completed로 재고를 예약+확정한다"는
 * 전체 시나리오를 증명한다(2.12).
 */
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"order.created", "payment.completed"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.consumer.group-id=inventory-service-test",
            "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class SagaListenersIntegrationTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("payment_lab_test").withUsername("cs").withPassword("cs123");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    OrderLineItemRepository orderLineItemRepository;

    @Test
    void order_created로_라인아이템을_캐시했다가_payment_completed로_재고를_예약하고_확정한다() {
        Product product = productRepository.save(new Product("Saga 리스너 테스트 상품", new BigDecimal("1000.0000"), "KRW"));
        inventoryRepository.save(new Inventory(product.getId(), 10));
        Long orderId = 777L;

        publish("order.created", orderId.toString(), EventType.ORDER_CREATED,
                new OrderCreatedPayload(orderId, product.getId(), 3, new BigDecimal("3000.0000"), "KRW"));

        awaitTrue(() -> orderLineItemRepository.findByOrderId(orderId).isPresent(),
                "orderId=" + orderId + "의 라인아이템이 아직 캐시되지 않았습니다");

        publish("payment.completed", orderId.toString(), EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(orderId, 1L, "tx-1", new BigDecimal("3000.0000"), "KRW", java.time.Instant.now()));

        awaitTrue(() -> {
            Inventory reloaded = inventoryRepository.findByProductId(product.getId()).orElseThrow();
            return reloaded.getAvailable() == 7 && reloaded.getReserved() == 0;
        }, "productId=" + product.getId() + "의 재고가 예약+확정되지 않았습니다");
    }

    private <T> void publish(String topic, String key, EventType eventType, T payload) {
        String json = objectMapper.writeValueAsString(EventEnvelopeFactory.create(eventType, payload));
        kafkaTemplate.send(topic, key, json);
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
