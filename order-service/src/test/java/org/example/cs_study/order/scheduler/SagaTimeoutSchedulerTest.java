package org.example.cs_study.order.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * minutes=0}으로 만든 주문은 생성 즉시 회수 대상이 된다.
 *
 * <p><b>자동 폴링을 꺼두고 {@link SagaTimeoutScheduler#reclaimTimedOutSagas}를 테스트가
 * 직접 부르는 이유</b>: 처음엔 스케줄러 주기를 짧게(200ms) 잡아 자동으로 돌게 했는데,
 * INVENTORY 테스트는 주문 생성 후 "결제 성공, 재고 응답 대기" 상태를 만들기까지 여러
 * 트랜잭션(markPaid, PAYMENT 스텝 succeed, currentStep advanceTo)을 순서대로 거친다 —
 * 그 사이에 스케줄러가 끼어들면 아직 PAYMENT 단계인 걸로 잘못 판단해 회수하거나,
 * {@code @Version} 낙관적 락(2.14)이 막아 예외를 던지는 등 결과가 CI 환경 속도에 따라
 * 달라졌다(CodeRabbit 리뷰, PR #71). 자동 폴링을 사실상 꺼두고(10분 지연) 상태를 다
 * 준비한 뒤 스케줄러를 직접 호출하면 두 테스트 모두 타이밍에 흔들리지 않는다.
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
        // 클래스 Javadoc 참고 — 자동 폴링과 테스트의 상태 준비가 경쟁하지 않도록 사실상
        // 꺼두고, 각 테스트가 SagaTimeoutScheduler.reclaimTimedOutSagas()를 직접 호출한다.
        registry.add("app.saga.timeout.scheduler.fixed-delay-ms", () -> "600000");
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

    @Autowired
    SagaTimeoutScheduler sagaTimeoutScheduler;

    @Test
    void PAYMENT_단계에서_응답이_안_오면_스케줄러가_주문을_취소한다() {
        OrderResponse order = orderService.createOrder(new CreateOrderRequest(30L, 1, new BigDecimal("1000.0000"), "KRW"));
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

        sagaTimeoutScheduler.reclaimTimedOutSagas();

        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.INVENTORY)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.FAILED);
        assertThat(sagaStepRepository
                        .findBySagaIdAndStepName(sagaInstance.getSagaId(), SagaStepName.PAYMENT)
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(SagaStepStatus.COMPENSATED);
        assertThat(sagaInstanceRepository.findById(sagaInstance.getSagaId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
    }

}
