package org.example.cs_study.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.example.cs_study.inventory.domain.Product;
import org.example.cs_study.inventory.dto.response.ProductResponse;
import org.example.cs_study.inventory.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * 로드맵 1.17 — TTL 만료 순간 동시 요청 폭주(Cache Stampede) 재현과 방어 전/후 비교.
 * TTL을 테스트용으로 짧게(500ms) 줘서 만료 시점을 빠르게 재현한다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "cache.products.ttl-ms=500")
class ProductCacheStampedeTest {

    private static final int CONCURRENT_REQUESTS = 50;

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
    ProductService productService;

    @Autowired
    ProductRepository productRepository;

    Long productId;

    @BeforeEach
    void setUp() {
        productId = productRepository.save(new Product("스탬피드 테스트 상품", new BigDecimal("5000.0000"), "KRW")).getId();
        productService.resetDbHitCount();
    }

    @Test
    void 무방어_캐시는_TTL_만료_순간_동시_요청이_전부_DB를_때린다() throws Exception {
        int dbHits = hitsAfterExpiryUnderConcurrency(productService::getProductUnprotected);

        // sync 없는 @Cacheable은 TTL 만료 직후 동시 요청을 막지 못한다 — 1건보다 훨씬 많이 DB를 때려야 한다.
        assertThat(dbHits).as("무방어 캐시는 스탬피드가 재현되어 DB 히트가 1보다 많아야 함").isGreaterThan(1);
    }

    @Test
    void sync_true_캐시는_TTL_만료_순간에도_DB를_한_번만_때린다() throws Exception {
        int dbHits = hitsAfterExpiryUnderConcurrency(productService::getProduct);

        // sync=true는 캐시 미스 시 첫 스레드만 통과시키고 나머지는 그 결과를 기다린다.
        assertThat(dbHits).as("sync=true 캐시는 스탬피드가 방어되어 DB 히트가 정확히 1이어야 함").isEqualTo(1);
    }

    private int hitsAfterExpiryUnderConcurrency(Function<Long, ProductResponse> call) throws Exception {
        call.apply(productId); // 캐시 워밍업
        productService.resetDbHitCount();

        Thread.sleep(700); // TTL(500ms) 만료 대기

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<ProductResponse>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                    .<Callable<ProductResponse>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await();
                        return call.apply(productId);
                    })
                    .collect(Collectors.toList());

            List<Future<ProductResponse>> futures = tasks.stream().map(pool::submit).collect(Collectors.toList());
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<ProductResponse> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            return productService.dbHitCount();
        } finally {
            pool.shutdownNow();
        }
    }
}
