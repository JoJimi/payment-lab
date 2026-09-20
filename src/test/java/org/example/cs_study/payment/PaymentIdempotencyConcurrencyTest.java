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
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.repository.OrderRepository;
import org.example.cs_study.payment.dto.request.RequestPaymentRequest;
import org.example.cs_study.payment.dto.response.PaymentResponse;
import org.example.cs_study.payment.repository.PaymentRepository;
import org.example.cs_study.payment.service.PaymentService;
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
 * 로드맵 1.9 — "같은 키로 100개 동시 요청 → 승인 1건만, 나머지는 동일 응답".
 * 1.5의 Mock PG를 인프로세스(Docker 불필요)로 같이 띄워 실제 HTTP 왕복까지 포함해 검증한다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIdempotencyConcurrencyTest {

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
    void 동일_멱등키로_100개_동시요청해도_승인은_한_건만_나고_나머지는_동일_응답이다() throws Exception {
        BigDecimal amount = new BigDecimal("1000.0000");
        String currency = "KRW";
        // PaymentService가 결제 전 주문을 조회/검증하므로(1.13 이후), 요청 금액/통화와
        // 일치하는 주문을 미리 만들어 둬야 한다.
        Order order = orderRepository.save(new Order(1L, 1, amount, currency));
        String idempotencyKey = UUID.randomUUID().toString();
        RequestPaymentRequest request = new RequestPaymentRequest(order.getId(), amount, currency);
        int concurrency = 100;

        // 풀 크기를 concurrency와 같게 잡는다 — 더 작으면 뒤에 밀린 태스크가 큐에서 시작조차
        // 못 한 채로 앞선 태스크들이 go.await()에서 블로킹돼, ready가 0에 도달하지 못하고
        // 매번 5초 타임아웃 후에야 진행된다(그마저도 "동시 100건"이 아니라 풀 크기만큼만 동시 실행됨).
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

            // 동시에 100번 요청해도 실제로 생성된 결제는 하나뿐이어야 한다 (승인 1건).
            assertThat(paymentIds).hasSize(1);
            assertThat(paymentRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
