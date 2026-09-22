package org.example.cs_study.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import org.example.cs_study.common.exception.BusinessException;
import org.example.cs_study.common.exception.ErrorCode;
import org.example.cs_study.mockpg.MockPgServer;
import org.example.cs_study.payment.dto.request.RequestPaymentRequest;
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
 * 2.1/2.3 — 원래 로드맵 1.10("Redis를 내린 상태에서 중복 요청 → DB 유니크 제약이 막아주는지
 * 확인")은 이 클래스가 검증했다. {@link PaymentIdempotencyConcurrencyTest} 상단 Javadoc과
 * 같은 이유로, "승인" 시나리오 자체가 성립하지 않아 범위를 좁혔다. Redis 다운 내성을 포함한
 * 멱등성 메커니즘 자체의 동시성 검증은 {@code common-idempotency} 모듈의
 * {@code IdempotencyRedisDownTest}로 옮겼다.
 *
 * <p>이 클래스는 payment-service 관점에서 하나만 남긴다: Redis가 죽은 상태에서도
 * {@code OrderValidator} 거부가 (DB 2차 방어를 거쳐) 정상적으로 일어나는지.
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

    @Test
    void redis가_다운된_상태에서도_주문_검증_거부는_정상적으로_동작한다() {
        redis.stop(); // 1차 방어 완전 제거. 이후 모든 Redis 호출은 DataAccessException.

        RequestPaymentRequest request = new RequestPaymentRequest(1L, new BigDecimal("2000.0000"), "KRW");
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> paymentService.requestPayment(idempotencyKey, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_IMPLEMENTED);

        assertThat(paymentRepository.count()).isZero();
    }
}
