package org.example.cs_study.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.OrderCreatedPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link OutboxService}가 증명해야 할 계약은 딱 두 가지다: (1) 저장한 값이 정확히
 * 남는가, (2) 호출자의 트랜잭션과 정말로 원자적으로 묶이는가(로드맵 부록 A-3의 존재
 * 이유 그 자체) — common-idempotency의 기존 테스트들과 같은 패턴(도메인 중립 컴포넌트는
 * 이 모듈 스스로 검증)을 따른다.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = OutboxServiceIntegrationTest.TestApp.class)
class OutboxServiceIntegrationTest {

    @SpringBootApplication
    static class TestApp {
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("outbox_test")
            .withUsername("cs")
            .withPassword("cs123");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway 없이 이 테스트 전용 스키마를 생성한다 — 마이그레이션 자체가 아니라
        // OutboxService의 저장/트랜잭션 동작을 검증하는 게 목적이다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // KafkaTemplate 빈이 뜨려면 bootstrap-servers가 필요하다(OutboxRelay가 같은 컨텍스트에
        // 함께 뜸). 실제 연결은 발행 시점에만 지연 생성되므로, 이 테스트(Kafka와 무관)에서는
        // 존재하지 않는 주소를 넣어도 컨텍스트 기동에 영향이 없다.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9");
    }

    @Autowired
    OutboxService outboxService;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void save하면_PENDING_상태로_봉투_전체가_직렬화돼_적재된다() {
        outboxService.save(
                EventType.ORDER_CREATED,
                "Order",
                "1",
                new OrderCreatedPayload(1L, 10L, 2, new BigDecimal("10000.0000"), "KRW"));

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo("1");
        assertThat(event.getEventType()).isEqualTo("order.created");
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload())
                .as("payload 컬럼에는 EventEnvelope 전체(eventId/traceId 포함)가 직렬화돼 들어간다")
                .contains("\"eventType\":\"order.created\"")
                .contains("\"orderId\":1");
    }

    @Test
    void 호출자의_트랜잭션이_롤백되면_outbox_row도_함께_사라진다() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                outboxService.save(
                        EventType.ORDER_CREATED,
                        "Order",
                        "2",
                        new OrderCreatedPayload(2L, 10L, 1, new BigDecimal("5000.0000"), "KRW"));
                throw new IllegalStateException("의도적 롤백 — 비즈니스 저장 실패를 흉내낸다");
            });
        } catch (IllegalStateException expected) {
            // 이 테스트가 검증하려는 바로 그 예외
        }

        assertThat(outboxEventRepository.findAll())
                .as("outbox insert가 호출자 트랜잭션에 올라탔다면, 트랜잭션 롤백 시 outbox row도 남지 않아야 함")
                .isEmpty();
    }
}
