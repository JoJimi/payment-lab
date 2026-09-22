package org.example.cs_study.order.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.InventoryReservedPayload;
import org.example.cs_study.event.payload.PaymentCompletedPayload;
import org.example.cs_study.order.OrderServiceApplication;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.OrderStatus;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.domain.saga.SagaStepStatus;
import org.example.cs_study.order.dto.request.CreateOrderRequest;
import org.example.cs_study.order.dto.response.OrderResponse;
import org.example.cs_study.order.repository.OrderRepository;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.example.cs_study.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * order-service 쪽 Saga 정상 흐름 리스너 2개({@link PaymentCompletedListener},
 * {@link InventoryReservedListener})가 실제로 이어 붙는지 끝까지 검증한다(2.12).
 * {@code OrderServiceApplication} 전체 컨텍스트 + 임베디드 Kafka + Testcontainers Postgres.
 *
 * <p>흐름: {@link OrderService#createOrder}로 실제 주문/Saga를 만든 뒤, payment-service와
 * inventory-service가 보냈을 법한 이벤트를 이 테스트가 대신 발행해 리스너를 트리거하고,
 * 매번 Saga/주문 상태와 (마지막 단계는) 실제로 Kafka까지 나간 {@code notification.requested}를
 * 확인한다.
 */
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"payment.completed", "inventory.reserved", "notification.requested"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = OrderServiceApplication.class,
        properties = {
            "app.outbox.relay.fixed-delay-ms=200",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.consumer.group-id=order-service-test",
            "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class SagaListenersIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("saga_listeners_test")
            .withUsername("cs")
            .withPassword("cs123");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    SagaStepRepository sagaStepRepository;

    @Test
    void payment_completed와_inventory_reserved를_차례로_받으면_Saga가_NOTIFICATION까지_전진하고_알림_이벤트가_발행된다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(20L, 1, new BigDecimal("3000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        publish(
                "payment.completed",
                order.id().toString(),
                EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(order.id(), 999L, "tx-1", new BigDecimal("3000.0000"), "KRW", Instant.now()));

        awaitOrderStatus(order.id(), OrderStatus.PAID);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.SUCCESS);
        awaitSagaCurrentStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY);

        publish(
                "inventory.reserved",
                order.id().toString(),
                EventType.INVENTORY_RESERVED,
                new InventoryReservedPayload(order.id(), 20L, 1));

        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY, SagaStepStatus.SUCCESS);
        awaitSagaCurrentStep(sagaInstance.getSagaId(), SagaStepName.NOTIFICATION);

        Consumer<String, String> consumer = createConsumer();
        try {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "notification.requested");
            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, "notification.requested", Duration.ofSeconds(10));
            assertThat(record.key()).isEqualTo(order.id().toString());
            assertThat(record.value()).contains("\"orderId\":" + order.id());
        } finally {
            consumer.close();
        }
    }

    private <T> void publish(String topic, String key, EventType eventType, T payload) {
        String json = objectMapper.writeValueAsString(EventEnvelopeFactory.create(eventType, payload));
        kafkaTemplate.send(topic, key, json);
    }

    private void awaitOrderStatus(Long orderId, OrderStatus expected) {
        awaitTrue(() -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            return order.getStatus() == expected;
        }, "orderId=" + orderId + "가 " + expected + "가 되지 않았습니다");
    }

    private void awaitSagaStep(String sagaId, SagaStepName stepName, SagaStepStatus expected) {
        awaitTrue(() -> sagaStepRepository
                .findBySagaIdAndStepName(sagaId, stepName)
                .map(SagaStep::getStatus)
                .filter(expected::equals)
                .isPresent(), "sagaId=" + sagaId + "의 " + stepName + " 스텝이 " + expected + "가 되지 않았습니다");
    }

    private void awaitSagaCurrentStep(String sagaId, SagaStepName expected) {
        awaitTrue(() -> sagaInstanceRepository
                .findById(sagaId)
                .map(SagaInstance::getCurrentStep)
                .filter(expected::equals)
                .isPresent(), "sagaId=" + sagaId + "의 currentStep이 " + expected + "로 전진하지 않았습니다");
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

    private Consumer<String, String> createConsumer() {
        var consumerProps = KafkaTestUtils.consumerProps(embeddedKafkaBroker, "saga-listeners-test-group", true);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, String>(consumerProps)
                .createConsumer();
    }
}
