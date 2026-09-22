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
 * 2.1/2.3 — 원래 로드맵 1.9("같은 키로 100개 동시 요청 → 승인 1건만, 나머지는 동일 응답")는
 * 이 클래스가 검증했다. payment-service가 2-B(Kafka Saga) 전까지 모든 결제 요청을 명시적으로
 * 거부하게 되면서(CodeRabbit 리뷰, {@code PaymentService.requestPayment} Javadoc 참고)
 * "승인 1건"이라는 시나리오 자체가 성립하지 않는다.
 *
 * <p>멱등성 메커니즘 자체(동시 요청 dedup, 실행 1회 보장)의 동시성 검증은
 * {@code common-idempotency} 모듈의 {@code IdempotencyAspectConcurrencyTest}로 옮겼다 —
 * 그 컴포넌트는 애초에 "도메인 중립"이라 특정 소비자(결제)의 비즈니스 로직에 얹혀 있을 이유가
 * 없다. 이 클래스는 payment-service 통합 관점에서 남는 두 가지만 검증한다: (1) 거부가
 * {@code OrderValidator} 게이트에서 일관되게 발생하는지, (2) 거부된 요청이 idempotency 레코드를
 * "진행 중"으로 영구히 붙잡아두지 않는지(같은 키로 재시도해도 매번 같은 방식으로 거부되는지).
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

    @Test
    void 주문_검증이_복원되기_전까지_결제_요청은_항상_명시적으로_거부된다() {
        RequestPaymentRequest request = new RequestPaymentRequest(1L, new BigDecimal("1000.0000"), "KRW");
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> paymentService.requestPayment(idempotencyKey, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_IMPLEMENTED);

        // 거부된 요청은 결제 행을 남기면 안 된다 — 검증 없이 승인하는 것보다는 낫지만,
        // 거부된 시도의 흔적이 남는 것도 데이터 무결성 관점에서 바람직하지 않다.
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void 거부된_요청은_멱등_레코드를_진행중_상태로_영구히_붙잡지_않는다() {
        RequestPaymentRequest request = new RequestPaymentRequest(1L, new BigDecimal("1000.0000"), "KRW");
        String idempotencyKey = UUID.randomUUID().toString();

        // IdempotencyAspect는 예외를 캐시하지 않고 IN_PROGRESS 마킹을 지운다(재시도 허용).
        // 같은 키로 3번 연속 호출해도 매번 독립적으로 같은 방식으로 거부돼야 한다 — 첫 시도가
        // 레코드를 영구히 점유해 두 번째부터 IdempotencyInProgressException(409)으로 막히면 안 된다.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> paymentService.requestPayment(idempotencyKey, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_IMPLEMENTED);
        }
    }
}
