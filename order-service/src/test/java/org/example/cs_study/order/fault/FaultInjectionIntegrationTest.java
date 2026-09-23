package org.example.cs_study.order.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.example.cs_study.common.outbox.OutboxEventRepository;
import org.example.cs_study.common.outbox.OutboxStatus;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.InventoryReservedPayload;
import org.example.cs_study.event.payload.PaymentCompletedPayload;
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
import org.example.cs_study.order.scheduler.SagaTimeoutScheduler;
import org.example.cs_study.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
 * 로드맵 2.17 — 각 서비스를 하나씩 "죽인" 상태로 주문을 진행시키고, Saga가 타임아웃으로
 * 이어지거나 보상되는지, 그리고 죽었던 서비스가 나중에 복구돼 뒤늦은 응답을 보내면
 * 시스템이 이를 안전하게 무시하는지 검증한다(이슈 #75).
 *
 * <p>"서비스를 죽인다"를 이 테스트에서는 "그 서비스가 응답 이벤트를 아예 발행하지 않는다"로
 * 시뮬레이션한다 — 이 프로젝트는 Kafka로만 연결된 MSA라, order-service 입장에서 payment/
 * inventory-service가 실제로 죽은 것과 응답이 영원히 안 오는 것은 관측적으로 구분할 수
 * 없다(부록 G-2 서비스 경계). {@link SagaTimeoutSchedulerTest}가 이미 이 "응답이 안 온다"는
 * 상태를 Postgres 상태만으로 직접 재현해 검증하지만, 이 클래스는 한 걸음 더 나아가
 * "타임아웃으로 회수된 뒤, 죽었던 서비스가 복구돼 뒤늦게 실제 Kafka 이벤트를 보내면" 실제
 * 리스너가 안전하게 반응하는지까지 {@code @EmbeddedKafka}로 끝까지 확인한다.
 *
 * <p><b>PAYMENT 복구 시나리오(1번)가 실제 버그를 하나 찾아냈다</b>: 이 테스트를 처음 작성할
 * 때 {@link org.example.cs_study.order.listener.PaymentCompletedListener}에는 상태 가드가
 * 없었다 — 타임아웃으로 이미 CANCELLED된 주문에 뒤늦은 {@code payment.completed}가 오면
 * {@code order.markPaid()}가 {@code InvalidStateTransitionException}을 던지고, 재시도
 * 3회(2.16) 후 DLT로 빠지는 게 실제 동작이었다. 다른 세 리스너({@link
 * org.example.cs_study.order.listener.InventoryReservedListener}, {@code PaymentFailedListener},
 * {@code InventoryFailedListener})는 이미 갖고 있던 상태 가드를 이 리스너에도 추가해
 * 고쳤다(리스너 클래스 Javadoc 참고) — 이 테스트가 그 가드를 고정한다.
 */
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "payment.completed",
            "inventory.reserved",
            "notification.requested",
            "payment.completed-dlt"
        })
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = OrderServiceApplication.class,
        properties = {
            "app.saga.timeout-minutes=0",
            // 자동 폴링과 테스트의 상태 준비가 경쟁하지 않도록 사실상 꺼두고(SagaTimeoutSchedulerTest와
            // 같은 이유), 각 테스트가 SagaTimeoutScheduler.reclaimTimedOutSagas()를 직접 호출한다.
            "app.saga.timeout.scheduler.fixed-delay-ms=600000",
            "app.outbox.relay.fixed-delay-ms=200",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.consumer.group-id=order-service-fault-test",
            "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class FaultInjectionIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("fault_injection_test")
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
    OutboxEventRepository outboxEventRepository;

    @Autowired
    SagaTimeoutScheduler sagaTimeoutScheduler;

    @Test
    void PAYMENT_서비스가_죽으면_타임아웃으로_보상되고_복구후_뒤늦은_응답은_무시된다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(40L, 1, new BigDecimal("1000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        // "payment-service가 죽었다" — payment.requested를 보낸 뒤 아무 응답도 안 온다.
        // timeout-minutes=0이라 생성 즉시 회수 대상이다.
        sagaTimeoutScheduler.reclaimTimedOutSagas();

        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaInstanceRepository.findById(sagaInstance.getSagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.FAILED);

        // "payment-service가 복구됐다" — 사실은 PG가 승인했던 결제라, 뒤늦게
        // payment.completed가 도착한다(PAYMENT/UNKNOWN 레이스, 이슈 #72와 같은 계열).
        publish(
                "payment.completed",
                order.id().toString(),
                EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(order.id(), 999L, "pg-late-tx", new BigDecimal("1000.0000"), "KRW", Instant.now()));

        // 리스너 클래스 Javadoc 참고 — 가드가 조용히 무시하므로 상태가 그대로 유지돼야 한다.
        // "바뀌지 않았다"를 직접 단언하기 전에, 리스너가 이벤트를 실제로 처리 시도했다는
        // 증거(DLT로도, processed_event로도 안 남는다는 게 오히려 함정이라 — 대신 일정
        // 시간 뒤에도 상태가 그대로인지로 확인한다)를 기다린다.
        sleepBriefly();
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaInstanceRepository.findById(sagaInstance.getSagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.FAILED);

        // 가드가 없었다면 예외 → 재시도 3회 소진 → payment.completed-dlt에 쌓였을 것이다.
        // 가드가 정상 동작하면 애초에 예외가 안 나므로 DLT는 비어 있어야 한다 — 이 자체가
        // "가드가 실제로 걸렸다"는 양성 증거다(부정 단언만으로는 "아직 처리 중"과 구분이 안
        // 되므로, sleepBriefly로 재시도 예산(500ms x 3)보다 넉넉히 기다린 뒤 확인한다).
        assertThat(awaitNoRecordsAfterWaiting("payment.completed-dlt")).isTrue();
    }

    @Test
    void INVENTORY_서비스가_죽으면_타임아웃으로_보상되고_복구후_뒤늦은_응답은_무시된다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(41L, 2, new BigDecimal("2000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        // "결제는 성공했는데 inventory-service가 죽었다" — SagaTimeoutSchedulerTest와 같은
        // 이유로 Kafka 없이 이 최종 상태를 직접 만든다(PaymentCompletedListener가 실제로
        // 만드는 것과 같은 상태: PAYMENT 성공, currentStep=INVENTORY).
        Order domainOrder = orderRepository.findById(order.id()).orElseThrow();
        domainOrder.markPaid();
        orderRepository.save(domainOrder);
        SagaStep paymentStep = sagaStepRepository
                .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                .orElseThrow();
        paymentStep.succeed("{}");
        sagaStepRepository.save(paymentStep);
        sagaInstance.advanceTo(SagaStepName.INVENTORY);
        sagaInstanceRepository.save(sagaInstance);

        sagaTimeoutScheduler.reclaimTimedOutSagas();

        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.COMPENSATED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.INVENTORY)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.FAILED);

        // "inventory-service가 복구됐다" — 뒤늦게 inventory.reserved가 도착한다.
        // InventoryReservedListener는 이미 상태 가드를 갖고 있다(2.15, CodeRabbit 리뷰) —
        // 이 테스트는 그 가드가 실제 Kafka 흐름에서도 걸리는지 끝까지 확인한다.
        publish(
                "inventory.reserved",
                order.id().toString(),
                EventType.INVENTORY_RESERVED,
                new InventoryReservedPayload(order.id(), 41L, 2));

        sleepBriefly();
        assertThat(sagaInstanceRepository.findById(sagaInstance.getSagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.INVENTORY)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.FAILED);
        // 가드가 currentStep을 건드리지 않았으므로 타임아웃 시점 그대로 INVENTORY에 머문다.
        assertThat(sagaInstanceRepository.findById(sagaInstance.getSagaId()).orElseThrow().getCurrentStep())
                .isEqualTo(SagaStepName.INVENTORY);
    }

    @Test
    void NOTIFICATION_서비스가_죽어도_주문은_PAID로_끝까지_완료된다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(42L, 1, new BigDecimal("3000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        publish(
                "payment.completed",
                order.id().toString(),
                EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(order.id(), 1L, "pg-tx", new BigDecimal("3000.0000"), "KRW", Instant.now()));
        publish(
                "inventory.reserved",
                order.id().toString(),
                EventType.INVENTORY_RESERVED,
                new InventoryReservedPayload(order.id(), 42L, 1));

        // notification-service를 이 테스트는 아예 기동하지 않는다 — order-service 모듈
        // 테스트라 애초에 그 서비스의 컨슈머가 없다. 즉 "notification-service가 죽어서
        // notification.requested를 영원히 못 받는다"는 상황이 이미 이 테스트 구성 자체로
        // 시뮬레이션된다. 그런데도 Saga/주문이 끝까지 완료되는지가 이 테스트의 핵심이다
        // (2.13에서 확정한 설계: 알림 발행 지시를 Outbox에 적재한 시점이 Saga 완료 기준).
        awaitOrderStatus(order.id(), OrderStatus.PAID);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);

        assertThat(outboxEventRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
                .extracting(event -> event.getEventType())
                .contains("notification.requested");

        // notification.requested가 실제로 Kafka에도 나갔는지(발행 자체는 됐는지)만 확인하고,
        // 일부러 소비하지 않는다 — "아무도 안 읽어도 주문 상태에는 영향 없다"가 검증 대상이다.
        assertThat(awaitRecord("notification.requested", order.id())).isNotNull();
    }

    private void sleepBriefly() {
        // DLQ 재시도 예산(500ms x 3회, 2.16)보다 넉넉히 기다린다 — 가드가 없었다면 이 안에
        // 재시도가 전부 소진돼 DLT까지 갔을 시간이다.
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private boolean awaitNoRecordsAfterWaiting(String topic) {
        Consumer<String, String> consumer = createConsumer();
        try {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, topic);
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
            return records.isEmpty();
        } finally {
            consumer.close();
        }
    }

    /** SagaListenersIntegrationTest의 같은 이름 헬퍼와 동일한 이유(여러 테스트가 토픽을 공유). */
    private org.apache.kafka.clients.consumer.ConsumerRecord<String, String> awaitRecord(String topic, Long orderId) {
        Consumer<String, String> consumer = createConsumer();
        try {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, topic);
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1));
                for (var record : records.records(topic)) {
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
        EventEnvelope<T> envelope = EventEnvelopeFactory.create(eventType, payload);
        String json = objectMapper.writeValueAsString(envelope);
        kafkaTemplate.send(topic, key, json);
    }

    private void awaitOrderStatus(Long orderId, OrderStatus expected) {
        awaitTrue(() -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            return order.getStatus() == expected;
        }, "orderId=" + orderId + "가 " + expected + "가 되지 않았습니다");
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
        var consumerProps = KafkaTestUtils.consumerProps(embeddedKafkaBroker, "fault-injection-test-" + UUID.randomUUID(), true);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
    }
}
