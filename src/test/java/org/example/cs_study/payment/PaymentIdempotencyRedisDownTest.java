package org.example.cs_study.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.example.cs_study.mockpg.MockPgServer;
import org.example.cs_study.order.Order;
import org.example.cs_study.order.OrderRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 로드맵 1.10 — "Redis를 내린 상태에서 중복 요청 → DB 유니크 제약이 막아주는지 확인".
 * Redis 컨테이너를 기동 후 멈춰서 1차 방어를 완전히 제거한 상태로 동시 요청을 보낸다.
 * {@code idempotency_keys.idempotency_key} unique 제약(2차 방어)만으로도 중복 승인이
 * 나지 않아야 한다 (부록 A-1).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIdempotencyRedisDownTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("payment_lab_test").withUsername("cs").withPassword("cs123");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static MockPgServer mockPgServer;

    @BeforeAll
    static void startMockPg() {
        mockPgServer = new MockPgServer();
    }

    @AfterAll
    static void stopMockPg() {
        mockPgServer.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        int port = mockPgServer.start(0);
        registry.add("mockpg.base-url", () -> "http://localhost:" + port);
    }

    @Autowired
    PaymentService paymentService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    OrderRepository orderRepository;

    @Test
    void redis가_다운된_상태에서도_동시_중복요청은_DB_유니크_제약이_막는다() throws Exception {
        BigDecimal amount = new BigDecimal("2000.0000");
        String currency = "KRW";
        // PaymentService가 결제 전 주문을 조회/검증하므로(1.13 이후), 요청 금액/통화와
        // 일치하는 주문을 미리 만들어 둬야 한다.
        Order order = orderRepository.save(new Order(1L, 1, amount, currency));

        redis.stop(); // 1차 방어 완전 제거. 이후 모든 Redis 호출은 DataAccessException.

        String idempotencyKey = UUID.randomUUID().toString();
        RequestPaymentRequest request = new RequestPaymentRequest(order.getId(), amount, currency);
        int concurrency = 20; // Redis 없이 DB만으로 방어하므로 100 스케일 테스트(1.9)보다 가볍게

        // 풀 크기를 concurrency와 같게 잡는다 — 더 작으면 뒤에 밀린 태스크가 시작도 못 한 채
        // 앞선 태스크들이 go.await()에서 블로킹돼 ready가 0에 도달하지 못한다.
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<PaymentResponse>> tasks = IntStream.range(0, concurrency)
                    .<Callable<PaymentResponse>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await();
                        return paymentService.requestPayment(idempotencyKey, request);
                    })
                    .collect(Collectors.toList());

            List<Future<PaymentResponse>> futures =
                    tasks.stream().map(pool::submit).collect(Collectors.toList());
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드가 출발선에 도달해야 함").isTrue();
            go.countDown();

            Set<Long> paymentIds = new java.util.HashSet<>();
            for (Future<PaymentResponse> future : futures) {
                PaymentResponse response = future.get(15, TimeUnit.SECONDS);
                paymentIds.add(response.id());
            }

            assertThat(paymentIds).hasSize(1);
            assertThat(paymentRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
