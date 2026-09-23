package org.example.cs_study.inventory.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.example.cs_study.common.inbox.ProcessedEventRepository;
import org.example.cs_study.event.EventEnvelope;
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

    @Autowired
    ProcessedEventRepository processedEventRepository;

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

    /**
     * 로드맵 2.18 — {@link PaymentCompletedListener}(이 파일)는 실제 재고 차감(reserve+confirm)이
     * 일어나는 유일한 지점이라, 이중 실행 시 **재고 이중 차감**이라는 이 프로젝트에서 가장
     * 심각한 실패 모드로 이어진다. 이 리스너는 {@link OrderLineItem#markReserved()}에
     * 상태 가드가 없고(단순 플래그 설정, 재호출해도 예외 없음), {@code Inventory.reserve()}도
     * 재고가 남아있는 한 재호출을 막지 않는다 — 그래서 다른 리스너들과 달리 "예외 발생 여부"가
     * 아니라 **재고 수치 자체**로 이중 실행 여부를 증명해야 한다. Kafka가 같은 eventId를
     * 재전달해도 {@code InboxService}가 두 번째 호출 자체를 막는지 이 테스트가 직접 확인한다.
     */
    @Test
    void payment_completed가_같은_eventId로_두_번_전달돼도_재고는_한_번만_차감된다() {
        Product product = productRepository.save(new Product("2.18 중복 주입 테스트 상품", new BigDecimal("1000.0000"), "KRW"));
        inventoryRepository.save(new Inventory(product.getId(), 10));
        Long orderId = 778L;

        publish("order.created", orderId.toString(), EventType.ORDER_CREATED,
                new OrderCreatedPayload(orderId, product.getId(), 3, new BigDecimal("3000.0000"), "KRW"));
        awaitTrue(() -> orderLineItemRepository.findByOrderId(orderId).isPresent(),
                "orderId=" + orderId + "의 라인아이템이 아직 캐시되지 않았습니다");

        EventEnvelope<PaymentCompletedPayload> envelope = EventEnvelopeFactory.create(
                EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(orderId, 1L, "tx-dup", new BigDecimal("3000.0000"), "KRW", Instant.now()));
        String json = objectMapper.writeValueAsString(envelope);

        // Kafka at-least-once 재전달 시뮬레이션 — 완전히 같은 메시지(같은 eventId)를 그대로 두 번 보낸다.
        kafkaTemplate.send("payment.completed", orderId.toString(), json);
        kafkaTemplate.send("payment.completed", orderId.toString(), json);

        awaitTrue(() -> processedEventRepository.findById(envelope.eventId()).isPresent(),
                "eventId=" + envelope.eventId() + "가 시간 내에 처리되지 않았습니다");

        // 재고 이중 차감이 있었다면 available이 10-2*3=4가 됐을 것이다(reserve+confirm 두 번).
        // 한 번만 처리됐다면 정확히 10-3=7이어야 한다.
        awaitTrue(() -> {
            Inventory reloaded = inventoryRepository.findByProductId(product.getId()).orElseThrow();
            return reloaded.getAvailable() == 7 && reloaded.getReserved() == 0;
        }, "productId=" + product.getId() + "의 재고가 예약+확정되지 않았습니다");

        // awaitTrue는 조건이 맞으면 즉시 반환하므로, "10-2*3=4로 잘못 됐다가 다시 안 바뀐다"는
        // 보장은 아니다 — 최종 값이 정확히 7(이중 차감이면 4)인지 한 번 더 명시적으로 단언한다.
        Inventory finalState = inventoryRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(finalState.getAvailable()).isEqualTo(7);
        assertThat(finalState.getReserved()).isZero();
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
