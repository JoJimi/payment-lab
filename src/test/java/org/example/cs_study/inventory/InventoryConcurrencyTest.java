package org.example.cs_study.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.example.cs_study.common.inventory.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 로드맵 1.12 — "재고 100개에 동시 요청 300건 → 정확히 100건 성공, 재고 0".
 * {@code inventory.lock-strategy} 4종 전부를 (스프링 컨텍스트 하나로) 직접 비교한다 —
 * 컨텍스트가 뜰 때 4개 {@link StockDeductor} 구현체가 전부 빈으로 등록되고,
 * {@code InventoryService}가 그중 하나만 기본으로 쓸 뿐이라 개별 구현체를 바로 주입받을 수 있다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InventoryConcurrencyTest {

    private static final int INITIAL_STOCK = 100;
    private static final int CONCURRENT_REQUESTS = 300;

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("payment_lab_test").withUsername("cs").withPassword("cs123");

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
    }

    @Autowired
    List<StockDeductor> deductors;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    Map<InventoryLockStrategy, StockDeductor> deductorsByStrategy;

    @BeforeEach
    void setUp() {
        deductorsByStrategy = deductors.stream().collect(Collectors.toMap(StockDeductor::strategy, Function.identity()));
        jdbcTemplate.update("DELETE FROM inventory");
        jdbcTemplate.update("DELETE FROM products");
    }

    @Test
    void 비관적_락은_재고_100개에_동시_300건_요청해도_정확히_100건만_성공한다() throws Exception {
        assertExactlyStockSizeSucceeds(InventoryLockStrategy.PESSIMISTIC);
    }

    @Test
    void 낙관적_락은_재고_100개에_동시_300건_요청해도_정확히_100건만_성공한다() throws Exception {
        assertExactlyStockSizeSucceeds(InventoryLockStrategy.OPTIMISTIC);
    }

    @Test
    void 분산_락은_재고_100개에_동시_300건_요청해도_정확히_100건만_성공한다() throws Exception {
        assertExactlyStockSizeSucceeds(InventoryLockStrategy.DISTRIBUTED);
    }

    @Test
    void 락_없음_전략은_lost_update로_재고_초과_판매가_난다() throws Exception {
        Long productId = seedProduct();
        StockDeductor deductor = deductorsByStrategy.get(InventoryLockStrategy.NONE);

        ConcurrencyResult result = runConcurrently(productId, deductor);

        // 락이 없으면 "재고 부족" 체크 자체가 stale read에 속아 100건보다 훨씬 많이 통과한다
        // (Lost Update). 정확한 통과 건수는 스케줄링에 따라 달라지므로 "100건보다 많다"만 단언한다.
        assertThat(result.successCount())
                .as("락 없이는 재고 부족 검증이 경합으로 무력화되어 100건보다 많이 성공해야 함 (Lost Update)")
                .isGreaterThan(INITIAL_STOCK);

        Integer finalAvailable = readAvailableDirectly(productId);
        assertThat(finalAvailable)
                .as("Lost Update로 실제 차감분과 DB에 반영된 값이 어긋나 0이 아니어야 함")
                .isNotZero();
    }

    private void assertExactlyStockSizeSucceeds(InventoryLockStrategy strategy) throws Exception {
        Long productId = seedProduct();
        StockDeductor deductor = deductorsByStrategy.get(strategy);

        ConcurrencyResult result = runConcurrently(productId, deductor);

        assertThat(result.successCount()).isEqualTo(INITIAL_STOCK);
        assertThat(result.failureCount()).isEqualTo(CONCURRENT_REQUESTS - INITIAL_STOCK);
        assertThat(readAvailableDirectly(productId)).isZero();
    }

    private Long seedProduct() {
        Product product = productRepository.save(new Product("동시성 테스트 상품", new BigDecimal("1000.0000"), "KRW"));
        inventoryRepository.save(new Inventory(product.getId(), INITIAL_STOCK));
        return product.getId();
    }

    private Integer readAvailableDirectly(Long productId) {
        return jdbcTemplate.queryForObject("SELECT available FROM inventory WHERE product_id = ?", Integer.class, productId);
    }

    private ConcurrencyResult runConcurrently(Long productId, StockDeductor deductor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        try {
            List<Callable<Void>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await();
                        try {
                            deductor.deduct(productId, 1);
                            success.incrementAndGet();
                        } catch (InsufficientStockException e) {
                            failure.incrementAndGet();
                        }
                        return null;
                    })
                    .collect(Collectors.toList());

            List<Future<Void>> futures = tasks.stream().map(pool::submit).collect(Collectors.toList());
            ready.await(5, TimeUnit.SECONDS);
            Instant start = Instant.now();
            go.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            return new ConcurrencyResult(success.get(), failure.get(), java.time.Duration.between(start, Instant.now()));
        } finally {
            pool.shutdownNow();
        }
    }

    private record ConcurrencyResult(int successCount, int failureCount, java.time.Duration elapsed) {
    }
}
