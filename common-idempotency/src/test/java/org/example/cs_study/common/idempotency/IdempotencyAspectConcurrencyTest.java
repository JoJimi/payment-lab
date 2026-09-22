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
 * 2.1 — 원래 1.9("같은 키로 100개 동시 요청 → 실행은 한 번만, 나머지는 동일 응답")는
 * payment-service의 결제 흐름에 얹혀 검증됐다({@code PaymentIdempotencyConcurrencyTest}).
 * 하지만 payment-service가 2-B(Kafka Saga) 전까지 모든 결제 요청을 명시적으로 거부하게
 * 되면서(CodeRabbit 리뷰 — 주문 검증 없이 결제를 조용히 승인하면 데이터 무결성이 깨짐)
 * 그 경로로는 더 이상 멱등성 메커니즘 자체를 증명할 수 없다.
 *
 * <p>{@code IdempotencyAspect}/{@code IdempotencyRecord}는 애초에 "도메인 중립"이라고
 * 스스로 문서화된 컴포넌트다({@link IdempotencyRecord} Javadoc) — 그 계약은 특정 소비자의
 * 비즈니스 로직이 아니라 이 모듈 자신이 증명해야 한다. 트리비얼한 카운터 메서드를 대상으로
 * 같은 동시성 시나리오를 재현한다.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // TestApp만 넘기면 @SpringBootApplication의 컴포넌트 스캔이 IdempotentCounterService를
        // 찾아줄 거라 생각하기 쉽지만, Spring Boot Test는 src/test에서 컴파일된 클래스를
        // 기본적으로 스캔에서 제외한다(TestTypeExcludeFilter — 테스트 픽스처가 의도치 않게 빈으로
        // 등록되는 걸 막기 위한 안전장치). classes 배열에 명시적으로 나열해야
        // (직접 import처럼 취급돼) 그 제외 필터를 우회해서 등록된다.
        classes = {IdempotencyAspectConcurrencyTest.TestApp.class, IdempotencyAspectConcurrencyTest.IdempotentCounterService.class})
class IdempotencyAspectConcurrencyTest {

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
        // Flyway 없이 이 테스트 전용 스키마를 생성한다 — 마이그레이션 자체가 아니라
        // AOP/Redis/DB dedup 동작을 검증하는 게 목적이다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    IdempotentCounterService counterService;

    @Test
    void 동일_멱등키로_100개_동시요청해도_실행은_한_번만_되고_나머지는_동일_응답이다() throws Exception {
        String key = UUID.randomUUID().toString();
        int concurrency = 100;

        // 풀 크기를 concurrency와 같게 잡는다 — 더 작으면 뒤에 밀린 태스크가 큐에서 시작조차
        // 못 한 채로 앞선 태스크들이 go.await()에서 블로킹돼, ready가 0에 도달하지 못한다.
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

            // 동시에 100번 요청해도 실제 실행은 한 번뿐이어야 하고(dedup), 나머지는 그 결과를 재현한다.
            assertThat(results).as("동시 100건 모두 같은 응답을 받아야 함").hasSize(1);
            assertThat(counterService.executionCount()).as("실제 실행은 한 번만 일어나야 함").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
