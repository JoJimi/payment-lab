package org.example.cs_study.common.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link InboxService}가 증명해야 할 계약: (1) 같은 eventId는 두 번째부터 비즈니스 로직을
 * 건너뛴다, (2) 비즈니스 로직이 실패하면 processed_event 기록도 함께 롤백돼 다음 재시도
 * 때 다시 시도할 수 있다 — common-idempotency/common-outbox와 같은 패턴으로 이 모듈
 * 스스로 검증한다.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = InboxServiceIntegrationTest.TestApp.class)
class InboxServiceIntegrationTest {

    @SpringBootApplication
    static class TestApp {
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inbox_test")
            .withUsername("cs")
            .withPassword("cs123");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway 없이 이 테스트 전용 스키마를 생성한다 — 마이그레이션 자체가 아니라
        // InboxService의 중복 차단/트랜잭션 동작을 검증하는 게 목적이다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    InboxService inboxService;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @AfterEach
    void cleanUp() {
        processedEventRepository.deleteAll();
    }

    @Test
    void 처음_보는_eventId는_처리하고_기록을_남긴다() {
        AtomicInteger executions = new AtomicInteger();

        boolean processed = inboxService.processIfNew("event-1", executions::incrementAndGet);

        assertThat(processed).isTrue();
        assertThat(executions.get()).isEqualTo(1);
        assertThat(processedEventRepository.existsById("event-1")).isTrue();
    }

    @Test
    void 같은_eventId가_다시_오면_비즈니스_로직을_건너뛴다() {
        AtomicInteger executions = new AtomicInteger();

        inboxService.processIfNew("event-2", executions::incrementAndGet);
        boolean secondAttempt = inboxService.processIfNew("event-2", executions::incrementAndGet);

        assertThat(secondAttempt)
                .as("Kafka는 at-least-once라 같은 이벤트가 두 번 올 수 있다 — 두 번째는 스킵돼야 함")
                .isFalse();
        assertThat(executions.get())
                .as("비즈니스 로직은 딱 한 번만 실행돼야 함")
                .isEqualTo(1);
    }

    @Test
    void 비즈니스_로직이_실패하면_처리_기록도_함께_롤백된다() {
        assertThatThrownBy(() -> inboxService.processIfNew("event-3", () -> {
                    throw new IllegalStateException("의도적 실패 — 비즈니스 처리 중 오류를 흉내낸다");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(processedEventRepository.existsById("event-3"))
                .as("처리가 실패했으면 다음 폴링에서 재시도할 수 있어야 하므로 기록이 남으면 안 됨")
                .isFalse();
    }
}
