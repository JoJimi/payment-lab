package org.example.cs_study.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.example.cs_study.common.outbox.OutboxEventRepository;
import org.example.cs_study.common.outbox.OutboxStatus;
import org.example.cs_study.order.OrderServiceApplication;
import org.example.cs_study.order.domain.OrderStatus;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStatus;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.example.cs_study.order.domain.saga.SagaStepStatus;
import org.example.cs_study.order.dto.request.CreateOrderRequest;
import org.example.cs_study.order.dto.response.OrderResponse;
import org.example.cs_study.order.repository.SagaInstanceRepository;
import org.example.cs_study.order.repository.SagaStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@link OrderService#createOrder}가 2.12에서 실제로 증명해야 할 것: 주문 저장, Saga 시작
 * (PAYMENT 단계), {@code order.created}/{@code payment.requested} Outbox 적재가 전부 한
 * 트랜잭션으로 성공한다. 실제 Kafka 발행(OutboxRelay가 폴링해서 보내는 부분)은
 * common-outbox의 OutboxRelayTest가 이미 증명했으므로 여기서는 Outbox 행 적재까지만 본다 —
 * Postgres만 있으면 되고 EmbeddedKafka는 필요 없다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = OrderServiceApplication.class)
class OrderServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("order_service_test")
            .withUsername("cs")
            .withPassword("cs123");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // Kafka 브로커가 없는 테스트 환경이라 OutboxRelay가 백그라운드에서 발행을 계속
        // 재시도하며 로그만 남긴다(설계상 예외를 삼킴) — 폴링 자체를 꺼서 노이즈를 줄인다.
        registry.add("app.outbox.relay.fixed-delay-ms", () -> "600000");
    }

    @Autowired
    OrderService orderService;

    @Autowired
    SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    SagaStepRepository sagaStepRepository;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Test
    void 주문을_생성하면_Saga가_PAYMENT_단계로_시작되고_두_이벤트가_Outbox에_쌓인다() {
        CreateOrderRequest request = new CreateOrderRequest(10L, 2, new BigDecimal("5000.0000"), "KRW");

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.sagaStatus()).isEqualTo(SagaStatus.STARTED);

        OrderResponse retrievedResponse = orderService.getOrder(response.id());
        assertThat(retrievedResponse.sagaStatus()).isEqualTo(SagaStatus.STARTED);

        assertThat(response.totalAmount()).isEqualByComparingTo("10000.0000");

        SagaInstance sagaInstance = sagaInstanceRepository.findByOrderId(response.id()).orElseThrow();
        assertThat(sagaInstance.getStatus()).isEqualTo(SagaStatus.STARTED);
        assertThat(sagaInstance.getCurrentStep()).isEqualTo(SagaStepName.PAYMENT);

        List<SagaStep> steps = sagaStepRepository.findBySagaId(sagaInstance.getSagaId());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStepName()).isEqualTo(SagaStepName.PAYMENT);
        assertThat(steps.get(0).getStatus()).isEqualTo(SagaStepStatus.PENDING);

        assertThat(outboxEventRepository.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING))
                .extracting(event -> event.getEventType())
                .contains("order.created", "payment.requested");
    }
}
