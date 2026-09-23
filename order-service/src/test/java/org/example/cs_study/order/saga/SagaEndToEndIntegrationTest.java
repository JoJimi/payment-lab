package org.example.cs_study.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.example.cs_study.common.outbox.OutboxEventRepository;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * 로드맵 2.19 — 이 프로젝트가 제대로 도는지 보여주는 정본(canonical) Saga 회귀
 * 스위트다. 2.11~2.18의 리스너별 테스트({@code SagaListenersIntegrationTest} 등)는 전부
 * {@code @EmbeddedKafka}(JVM 인프로세스 브로커)를 썼는데, 이 클래스만 진짜 Testcontainers
 * Kafka 컨테이너를 띄운다 — 파티션 배정/컨슈머 그룹 리밸런싱 등 실제 브로커 동작에 더
 * 가까운 환경에서, 핵심 시나리오 4개(정상 1건 + 보상 3건)가 전부 맞물려 돌아가는지 마지막으로
 * 한 번 더 확인한다.
 *
 * <p>기존 리스너별 테스트를 대체하지 않는다 — 저 테스트들은 각 리스너의 세부 계약(가드,
 * 중복 처리, DLQ 등)을 검증하는 역할로 계속 남는다. 이 클래스는 "전체가 이어 붙어 도는가"만
 * 본다. 부록 E-3 원칙대로, CI 시간이 늘더라도 이 테스트는 nightly로 빼지 않는다 — Saga
 * 보상 테스트는 이 프로젝트의 존재 이유다.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = OrderServiceApplication.class,
        properties = {
            "app.saga.timeout-minutes=0",
            // 자동 폴링과 테스트의 상태 준비가 경쟁하지 않도록 사실상 꺼둔다(SagaTimeoutSchedulerTest와
            // 같은 이유) — 타임아웃 시나리오는 스케줄러를 직접 호출한다.
            "app.saga.timeout.scheduler.fixed-delay-ms=600000",
            "app.outbox.relay.fixed-delay-ms=200",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.consumer.group-id=order-service-e2e-test",
            "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class SagaEndToEndIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("saga_e2e_test")
            .withUsername("cs")
            .withPassword("cs123");

    // docker-compose.yml의 로컬 Kafka(2.5)와 같은 이미지를 써서 이 테스트가 실제로 검증하려는
    // "운영에 가까운 브로커 동작"이 로컬 환경과도 일치하게 한다(troubleshooting #12).
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

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
    void 정상_흐름_주문생성부터_알림발행까지_Saga가_끝까지_완료된다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(60L, 1, new BigDecimal("1000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        publish(
                "payment.completed",
                order.id().toString(),
                EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(order.id(), 1L, "tx-e2e-1", new BigDecimal("1000.0000"), "KRW", Instant.now()));
        awaitSagaCurrentStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY);

        publish(
                "inventory.reserved",
                order.id().toString(),
                EventType.INVENTORY_RESERVED,
                new InventoryReservedPayload(order.id(), 60L, 1));

        awaitOrderStatus(order.id(), OrderStatus.PAID);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);
        assertThat(outboxEventRepository.findAll())
                .filteredOn(event -> event.getAggregateId().equals(order.id().toString())
                        && event.getEventType().equals("notification.requested"))
                .hasSize(1);
    }

    @Test
    void 보상_1_PAYMENT_실패시_보상없이_곧장_주문을_취소하고_Saga를_완료한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(61L, 1, new BigDecimal("2000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        publish(
                "payment.failed",
                order.id().toString(),
                EventType.PAYMENT_FAILED,
                new PaymentFailedPayload(order.id(), 2L, new BigDecimal("2000.0000"), "KRW", "INSUFFICIENT_FUNDS"));

        awaitOrderStatus(order.id(), OrderStatus.CANCELLED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.FAILED);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);
    }

    @Test
    void 보상_2_INVENTORY_실패시_결제_스텝을_보상대상으로_표시하고_주문을_취소한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(62L, 5, new BigDecimal("500.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        publish(
                "payment.completed",
                order.id().toString(),
                EventType.PAYMENT_COMPLETED,
                new PaymentCompletedPayload(order.id(), 3L, "tx-e2e-2", new BigDecimal("500.0000"), "KRW", Instant.now()));
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.SUCCESS);

        publish(
                "inventory.failed",
                order.id().toString(),
                EventType.INVENTORY_FAILED,
                new InventoryFailedPayload(order.id(), 62L, 5, "OUT_OF_STOCK"));

        awaitOrderStatus(order.id(), OrderStatus.CANCELLED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY, SagaStepStatus.FAILED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.COMPENSATED);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);
    }

    /**
     * 보상 3 — 응답 이벤트 자체가 안 오는 경우(2.15). 다른 세 시나리오와 달리 Kafka 이벤트를
     * 기다리지 않는다 — {@code timeout-minutes=0}이라 생성 즉시 회수 대상이고,
     * {@code SagaTimeoutScheduler}를 직접 호출해 타이밍에 흔들리지 않게 한다
     * (SagaTimeoutSchedulerTest와 같은 이유).
     */
    @Test
    void 보상_3_응답이_안_오면_타임아웃_스케줄러가_회수해_주문을_취소한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(63L, 1, new BigDecimal("1500.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        sagaTimeoutScheduler.reclaimTimedOutSagas();

        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.FAILED);
        assertThat(sagaInstanceRepository.findById(sagaInstance.getSagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
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
                .map(org.example.cs_study.order.domain.saga.SagaStep::getStatus)
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
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
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
