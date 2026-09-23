package org.example.cs_study.order.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import org.example.cs_study.common.outbox.OutboxEventRepository;
import org.example.cs_study.common.outbox.OutboxStatus;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@link SagaTimeoutScheduler}가 실패 이벤트가 아예 오지 않는 Saga를 실제로 회수하는지
 * 검증한다(2.15) — {@code OrderServiceIntegrationTest}와 같은 이유로 Postgres만 있으면
 * 되고 Kafka는 필요 없다(스케줄러는 로컬 상태만 보고 판단한다). {@code app.saga.timeout-
 * minutes=0}으로 만든 주문은 생성 즉시 회수 대상이 되고, {@code app.saga.timeout.scheduler.
 * fixed-delay-ms}를 짧게 잡아 스케줄러가 곧바로 돌게 한다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = OrderServiceApplication.class)
class SagaTimeoutSchedulerTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("saga_timeout_test")
            .withUsername("cs")
            .withPassword("cs123");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // Kafka 브로커가 없어 OutboxRelay가 계속 실패 로그만 남긴다(OrderServiceIntegrationTest와
        // 같은 이유) — 폴링 자체를 꺼서 노이즈를 줄인다. 이 테스트는 Outbox 행 적재까지만 본다.
        registry.add("app.outbox.relay.fixed-delay-ms", () -> "600000");
        registry.add("app.saga.timeout-minutes", () -> "0");
        registry.add("app.saga.timeout.scheduler.fixed-delay-ms", () -> "200");
    }

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

    @Test
    void PAYMENT_단계에서_응답이_안_오면_스케줄러가_주문을_취소한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(30L, 1, new BigDecimal("1000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        awaitOrderStatus(order.id(), OrderStatus.CANCELLED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.FAILED);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);

        assertThat(outboxEventRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
                .extracting(event -> event.getEventType())
                .contains("order.cancelled", "notification.requested");
    }

    @Test
    void INVENTORY_단계에서_응답이_안_오면_결제_스텝을_보상대상으로_표시하고_주문을_취소한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(31L, 2, new BigDecimal("2000.0000"), "KRW"));
        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(order.id()).orElseThrow();

        // Kafka 없이 이 테스트만으로 "결제는 끝났는데 재고 응답이 안 왔다"는 상태를 직접
        // 만든다 — PaymentCompletedListener(2.12)가 payment.completed 수신 시 만드는 것과
        // 같은 최종 상태(PAYMENT 스텝 SUCCESS, currentStep=INVENTORY)를 리스너 없이 재현한다.
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

        awaitOrderStatus(order.id(), OrderStatus.CANCELLED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.INVENTORY, SagaStepStatus.FAILED);
        awaitSagaStep(sagaInstance.getSagaId(), SagaStepName.PAYMENT, SagaStepStatus.COMPENSATED);
        awaitSagaStatus(sagaInstance.getSagaId(), SagaStatus.COMPLETED);
    }

    private void awaitOrderStatus(Long orderId, OrderStatus expected) {
        awaitTrue(() -> orderRepository.findById(orderId).map(Order::getStatus).filter(expected::equals).isPresent(),
                "orderId=" + orderId + "가 " + expected + "가 되지 않았습니다");
    }

    private void awaitSagaStep(String sagaId, SagaStepName stepName, SagaStepStatus expected) {
        awaitTrue(() -> sagaStepRepository
                .findBySagaIdAndStepName(sagaId, stepName)
                .map(SagaStep::getStatus)
                .filter(expected::equals)
                .isPresent(), "sagaId=" + sagaId + "의 " + stepName + " 스텝이 " + expected + "가 되지 않았습니다");
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
}
