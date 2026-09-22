package org.example.cs_study.order.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.example.cs_study.common.inbox.ProcessedEventRepository;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.InventoryFailedPayload;
import org.example.cs_study.event.payload.InventoryReservedPayload;
import org.example.cs_study.event.payload.PaymentCompletedPayload;
import org.example.cs_study.event.payload.PaymentFailedPayload;
import org.example.cs_study.order.OrderServiceApplication;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.OrderStatus;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
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
 * order-service 쪽 Saga 리스너들이 실제로 이어 붙는지 끝까지 검증한다 — 정상 흐름 2개
 * ({@link PaymentCompletedListener}, {@link InventoryReservedListener}, 2.12)와 보상 흐름
 * 2개({@link PaymentFailedListener}, {@link InventoryFailedListener}, 2.13).
 * {@code OrderServiceApplication} 전체 컨텍스트 + 임베디드 Kafka + Testcontainers Postgres.
 *
 * <p>흐름: {@link OrderService#createOrder}로 실제 주문/Saga를 만든 뒤, payment-service와
 * inventory-service가 보냈을 법한 이벤트를 이 테스트가 대신 발행해 리스너를 트리거하고,
 * 매번 Saga/주문 상태와 (마지막 단계는) 실제로 Kafka까지 나간 이벤트를 확인한다.
 */
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "payment.completed",
            "inventory.reserved",
            "notification.requested",
            "payment.failed",
            "inventory.failed",
            "order.cancelled"
        })
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

    @Autowired
    ProcessedEventRepository processedEventRepository;

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

        ConsumerRecord<String, String> record = awaitRecord("notification.requested", order.id());
        assertThat(record.value()).contains("\"orderId\":" + order.id());

        // 2.13: notification.requested가 나간 시점에 Saga가 바로 COMPLETED로 확정된다(알림
        // 전달 확인을 기다리지 않음, InventoryReservedListener Javadoc 참고).
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);
    }

    @Test
    void payment_failed를_받으면_보상없이_곧장_주문을_취소하고_Saga를_완료한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(21L, 1, new BigDecimal("1000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        publish(
                "payment.failed",
                order.id().toString(),
                EventType.PAYMENT_FAILED,
                new PaymentFailedPayload(order.id(), 111L, new BigDecimal("1000.0000"), "KRW", "INSUFFICIENT_FUNDS"));

        awaitOrderStatus(order.id(), OrderStatus.CANCELLED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.FAILED);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);

        assertNotificationRequestedPublished(order.id());
    }

    @Test
    void payment_failed가_서로_다른_eventId로_중복_발행돼도_안전하다() {
        // 2.14: InboxService는 eventId가 같은 재전달만 막는다 — 같은 orderId에 대해
        // payment.failed가 서로 다른 eventId로 두 번 발행되는 상황(예: payment-service의
        // 버그로 인한 중복 발행)까지 리스너 자체가 흡수하는지 확인한다. publish()가 매번
        // EventEnvelopeFactory로 새 eventId를 만들어주므로 이 두 번의 publish는 서로 다른
        // eventId를 갖는다 — InboxService의 중복 방지로는 못 막고, PaymentFailedListener의
        // 자체 상태 가드(sagaInstance.status != STARTED면 무시)가 막아야 한다.
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(23L, 1, new BigDecimal("1000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        PaymentFailedPayload payload =
                new PaymentFailedPayload(order.id(), 112L, new BigDecimal("1000.0000"), "KRW", "INSUFFICIENT_FUNDS");
        publish("payment.failed", order.id().toString(), EventType.PAYMENT_FAILED, payload);

        awaitOrderStatus(order.id(), OrderStatus.CANCELLED);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);
        assertNotificationRequestedPublished(order.id());

        String secondEventId = publishAndGetEventId("payment.failed", order.id().toString(), EventType.PAYMENT_FAILED, payload);

        // processed_event에 이 eventId가 기록됐다는 것 자체가 InboxService.processIfNew가
        // 비즈니스 로직(가드 포함)을 예외 없이 끝까지 실행하고 커밋했다는 증거다 — 단순히
        // "상태가 그대로였다"만 보면 가드가 걸러낸 것인지 리스너가 처리 중 죽은 것인지
        // 구분이 안 된다(CodeRabbit 리뷰, 2.14).
        awaitEventProcessed(secondEventId);
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaInstanceRepository.findById(sagaInstance.getSagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    void inventory_failed를_받으면_결제_스텝을_보상대상으로_표시하고_주문을_취소한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(22L, 5, new BigDecimal("500.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        publish(
                "payment.completed",
                order.id().toString(),
                EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(order.id(), 998L, "tx-2", new BigDecimal("500.0000"), "KRW", Instant.now()));
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.SUCCESS);

        publish(
                "inventory.failed",
                order.id().toString(),
                EventType.INVENTORY_FAILED,
                new InventoryFailedPayload(order.id(), 22L, 5, "OUT_OF_STOCK"));

        awaitOrderStatus(order.id(), OrderStatus.CANCELLED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY, SagaStepStatus.FAILED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.COMPENSATED);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);

        assertNotificationRequestedPublished(order.id());

        ConsumerRecord<String, String> record = awaitRecord("order.cancelled", order.id());
        assertThat(record.value()).contains("\"orderId\":" + order.id());
    }

    private void assertNotificationRequestedPublished(Long orderId) {
        ConsumerRecord<String, String> record = awaitRecord("notification.requested", orderId);
        assertThat(record.value()).contains("\"orderId\":" + orderId).contains("ORDER_CANCELLED");
    }

    /**
     * 세 테스트 메서드가 {@code notification.requested} 같은 토픽을 공유한다(클래스 레벨
     * {@code @EmbeddedKafka} 브로커 하나를 재사용) — {@code KafkaTestUtils.getSingleRecord}는
     * "정확히 레코드 1개"를 전제해서, 다른 테스트가 먼저 남긴 레코드까지 같이 잡히면
     * {@code IllegalStateException: More than one record for topic found}로 깨진다(CI에서
     * 실제로 겪음). 매번 새 컨슈머 그룹으로 토픽 전체를 읽어 이 테스트의 {@code orderId}와
     * 일치하는 레코드만 골라내는 방식이라 다른 테스트의 레코드가 섞여도 안전하다.
     */
    private ConsumerRecord<String, String> awaitRecord(String topic, Long orderId) {
        Consumer<String, String> consumer = createConsumer();
        try {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, topic);
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records.records(topic)) {
                    if (orderId.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
            throw new AssertionError(topic + "에서 orderId=" + orderId + "인 레코드를 시간 내에 찾지 못했습니다");
        } finally {
            consumer.close();
        }
    }

    private <T> void publish(String topic, String key, EventType eventType, T payload) {
        publishAndGetEventId(topic, key, eventType, payload);
    }

    /** @return 발행한 이벤트의 eventId — 중복 발행 테스트가 InboxService 처리 완료를 기다릴 때 쓴다. */
    private <T> String publishAndGetEventId(String topic, String key, EventType eventType, T payload) {
        EventEnvelope<T> envelope = EventEnvelopeFactory.create(eventType, payload);
        String json = objectMapper.writeValueAsString(envelope);
        kafkaTemplate.send(topic, key, json);
        return envelope.eventId();
    }

    /**
     * {@code processed_event}에 이 eventId가 실제로 기록됐는지 기다린다(2.14, CodeRabbit
     * 리뷰) — 상태가 안 바뀌었다는 것만으로는 "가드가 정상적으로 걸러냈다"와 "리스너가 아예
     * 이 이벤트를 처리하다 죽었다"를 구분할 수 없다. {@code InboxService.processIfNew}가 이
     * 행을 커밋해야만 비즈니스 로직(가드 포함)이 예외 없이 끝까지 실행됐다고 확신할 수 있다.
     */
    private void awaitEventProcessed(String eventId) {
        awaitTrue(
                () -> processedEventRepository.findById(eventId).isPresent(),
                "eventId=" + eventId + "가 시간 내에 처리되지 않았습니다");
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

    private void awaitSagaStatus(String sagaId, SagaStatus expected) {
        awaitTrue(() -> sagaInstanceRepository
                .findById(sagaId)
                .map(SagaInstance::getStatus)
                .filter(expected::equals)
                .isPresent(), "sagaId=" + sagaId + "의 status가 " + expected + "가 되지 않았습니다");
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
        // 매 호출마다 새 컨슈머 그룹 — 여러 테스트가 같은 토픽을 공유해도 그룹 오프셋
        // 커밋 타이밍에 서로 영향을 주지 않는다(awaitRecord Javadoc 참고).
        var consumerProps = KafkaTestUtils.consumerProps(embeddedKafkaBroker, "saga-listeners-test-" + UUID.randomUUID(), true);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, String>(consumerProps)
                .createConsumer();
    }
}
