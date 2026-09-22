package org.example.cs_study.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.example.cs_study.order.domain.saga.SagaInstance;
import org.example.cs_study.order.domain.saga.SagaStep;
import org.example.cs_study.order.domain.saga.SagaStepName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@link SagaInstanceRepository}/{@link SagaStepRepository}가 V4__saga.sql 스키마와 실제로
 * 맞물리는지 검증한다 — 엔티티 매핑 오타나 제약 조건 누락은 순수 단위 테스트로는 못 잡는다.
 * OrderServiceApplicationTests와 동일하게 전체 스프링 컨텍스트 + Testcontainers를 쓴다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SagaRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payment_lab_test")
            .withUsername("cs")
            .withPassword("cs123");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    SagaStepRepository sagaStepRepository;

    @Test
    void saga_인스턴스를_저장하고_sagaId_orderId로_각각_조회할_수_있다() {
        SagaInstance saved = sagaInstanceRepository.save(
                new SagaInstance(100L, Instant.now().plus(5, ChronoUnit.MINUTES)));

        assertThat(sagaInstanceRepository.findById(saved.getSagaId())).isPresent();
        assertThat(sagaInstanceRepository.findByOrderId(100L))
                .isPresent()
                .get()
                .extracting(SagaInstance::getSagaId)
                .isEqualTo(saved.getSagaId());
    }

    @Test
    void 같은_orderId로_두_번째_saga_인스턴스를_저장하면_유니크_제약에_막힌다() {
        sagaInstanceRepository.save(new SagaInstance(200L, Instant.now().plus(5, ChronoUnit.MINUTES)));

        assertThatThrownBy(() ->
                        sagaInstanceRepository.save(new SagaInstance(200L, Instant.now().plus(5, ChronoUnit.MINUTES))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void saga_스텝을_저장하고_sagaId로_생성_순서대로_조회할_수_있다() {
        SagaInstance saga = sagaInstanceRepository.save(
                new SagaInstance(300L, Instant.now().plus(5, ChronoUnit.MINUTES)));

        sagaStepRepository.save(new SagaStep(saga.getSagaId(), SagaStepName.PAYMENT, "{}"));
        sagaStepRepository.save(new SagaStep(saga.getSagaId(), SagaStepName.INVENTORY, "{}"));

        List<SagaStep> steps = sagaStepRepository.findBySagaId(saga.getSagaId());

        assertThat(steps).extracting(SagaStep::getStepName)
                .containsExactly(SagaStepName.PAYMENT, SagaStepName.INVENTORY);
    }
}
