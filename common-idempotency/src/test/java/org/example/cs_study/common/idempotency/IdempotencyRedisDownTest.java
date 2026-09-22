package org.example.cs_study.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 2.1 — 원래 1.10("Redis를 내린 상태에서 중복 요청 → DB 유니크 제약이 막아주는지 확인")도
 * payment-service의 결제 흐름에 얹혀 검증됐다({@code PaymentIdempotencyRedisDownTest}).
 * {@link IdempotencyAspectConcurrencyTest} 상단 Javadoc과 같은 이유로 이 모듈에서
 * 트리비얼한 대상으로 재현한다.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // TestApp만 넘기면 컴포넌트 스캔이 IdempotentCounterService를 찾아줄 거라 생각하기 쉽지만,
        // Spring Boot Test는 src/test에서 컴파일된 클래스를 기본적으로 스캔에서 제외한다
        // (TestTypeExcludeFilter). classes 배열에 명시적으로 나열해야 그 제외 필터를 우회한다.
        classes = {IdempotencyRedisDownTest.TestApp.class, IdempotencyRedisDownTest.IdempotentCounterService.class})
class IdempotencyRedisDownTest {

    @SpringBootApplication
    static class TestApp {
    }

    @Component
    static class IdempotentCounterService {
        private final AtomicInteger executions = new AtomicInteger();

        @Idempotent(key = "#key")
        public String execute(String key) {
            return "result-" + executions.incrementAndGet();
        }

        int executionCount() {
            return executions.get();
        }
    }

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("idempotency_test")
            .withUsername("cs")
            .withPassword("cs123");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    IdempotentCounterService counterService;

    @Test
    void redis가_다운된_상태에서도_동시_중복요청은_DB_유니크_제약이_막는다() throws Exception {
        String key = UUID.randomUUID().toString();

        redis.stop(); // 1차 방어 완전 제거. 이후 모든 Redis 호출은 DataAccessException.

        int concurrency = 20; // Redis 없이 DB만으로 방어하므로 100 스케일 테스트보다 가볍게

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<String>> tasks = IntStream.range(0, concurrency)
                    .<Callable<String>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await();
                        return counterService.execute(key);
                    })
                    .collect(Collectors.toList());

            List<Future<String>> futures =
                    tasks.stream().map(pool::submit).collect(Collectors.toList());
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드가 출발선에 도달해야 함").isTrue();
            go.countDown();

            Set<String> results = new HashSet<>();
            for (Future<String> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }

            assertThat(results).hasSize(1);
            assertThat(counterService.executionCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
