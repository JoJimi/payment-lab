package org.example.cs_study;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R.CI6 — Boot 4 "조용한 실패" 3종 자동 검증 (docs/troubleshooting/00-spring-boot-4.md).
 *
 * Flyway / AOP / Tracing은 빌드·기동이 성공해도 해당 기능만 조용히 꺼질 수 있어
 * 사람이 의존성을 바꿀 때마다 수동으로 확인할 수 없다. build-test 잡에서 항상 돈다.
 * 이 3개가 깨지면 이후 단계(1.9 멱등성, 4.4 traceId 조회)의 실패가
 * 로직 버그인지 인프라 미동작인지 구분되지 않는다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(QuietFailureRegressionTest.AopTestConfig.class)
class QuietFailureRegressionTest {

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
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AopTarget aopTarget;

    @Autowired
    InterceptCounter interceptCounter;

    @Autowired
    RestTestClient restTestClient;

    @Test
    void flyway_마이그레이션이_기동_시점에_실행된다() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history", Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void aop_어드바이스가_실제로_프록시를_가로챈다() {
        aopTarget.ping();

        assertThat(interceptCounter.get()).isEqualTo(1);
    }

    @Test
    void tracing이_요청_로그의_MDC에_traceId를_채운다() {
        Logger appLogger = (Logger) LoggerFactory.getLogger("org.example.cs_study");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        appLogger.addAppender(appender);

        try {
            restTestClient.get().uri("/ping").exchange().expectStatus().isOk();
        } finally {
            appLogger.detachAppender(appender);
        }

        boolean traceIdPresent = appender.list.stream()
                .map(event -> event.getMDCPropertyMap().get("traceId"))
                .anyMatch(traceId -> traceId != null && !traceId.isBlank());
        assertThat(traceIdPresent)
                .as("PingController 로그의 MDC에 traceId가 채워져야 함 (micrometer-tracing-bridge-brave 동작 증거)")
                .isTrue();
    }

    interface AopTarget {
        String ping();
    }

    static class AopTargetImpl implements AopTarget {
        @Override
        public String ping() {
            return "pong";
        }
    }

    static class InterceptCounter {
        private final AtomicInteger count = new AtomicInteger();

        void increment() {
            count.incrementAndGet();
        }

        int get() {
            return count.get();
        }
    }

    @Aspect
    static class CountingAspect {
        private final InterceptCounter counter;

        CountingAspect(InterceptCounter counter) {
            this.counter = counter;
        }

        @Around("execution(* org.example.cs_study.QuietFailureRegressionTest.AopTarget.ping(..))")
        Object countInvocation(ProceedingJoinPoint pjp) throws Throwable {
            counter.increment();
            return pjp.proceed();
        }
    }

    @TestConfiguration
    static class AopTestConfig {
        @Bean
        InterceptCounter interceptCounter() {
            return new InterceptCounter();
        }

        @Bean
        AopTarget aopTarget() {
            return new AopTargetImpl();
        }

        @Bean
        CountingAspect countingAspect(InterceptCounter counter) {
            return new CountingAspect(counter);
        }
    }
}
