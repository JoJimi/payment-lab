package org.example.cs_study.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.example.cs_study.mockpg.MockPgServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 3.2 — {@link ResilientMockPgGateway}가 CircuitBreaker를 실제로 올바르게 감싸는지
 * 증명한다. Spring 컨텍스트도 Docker도 필요 없다 — {@link MockPgServer}를 인프로세스로
 * 띄우고(1.5, {@code PaymentIdempotencyConcurrencyTest}와 같은 패턴), 순수 Resilience4j
 * core API로 CircuitBreaker를 직접 구성한다.
 */
class ResilientMockPgGatewayTest {

    private static MockPgServer mockPgServer;
    private static String baseUrl;

    @BeforeAll
    static void startMockPg() throws IOException {
        mockPgServer = new MockPgServer();
        int port = mockPgServer.start(0);
        baseUrl = "http://localhost:" + port;
    }

    @AfterAll
    static void stopMockPg() {
        mockPgServer.stop();
    }

    private ResilientMockPgGateway newGateway(CircuitBreakerConfig config) {
        MockPgClient client = new MockPgClient(baseUrl);
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        return new ResilientMockPgGateway(client, registry);
    }

    @Test
    void 정상_응답이면_CircuitBreaker를_거쳐도_승인_결과가_그대로_전달된다() throws IOException {
        configureMockPg(0, 0.0, null, false);
        ResilientMockPgGateway gateway = newGateway(defaultConfig());

        MockPgResult result = gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");

        assertThat(result.outcome()).isEqualTo(MockPgOutcome.APPROVED);
    }

    @Test
    void 카드_거절같은_정상_비즈니스_실패는_서킷을_열지_않는다() throws IOException {
        // failureRate=1.0: 매번 정상적으로 402(PG_DECLINED)를 반환한다 — 이건 기술적
        // 실패가 아니라 정상 응답이다. CircuitBreaker는 예외가 나야만 반응하므로, 이
        // 시나리오에서는 아무리 반복해도 서킷이 열리면 안 된다.
        configureMockPg(0, 1.0, null, false);
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(3)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        ResilientMockPgGateway gateway = newGateway(config);

        for (int i = 0; i < 5; i++) {
            MockPgResult result = gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
            assertThat(result.outcome()).isEqualTo(MockPgOutcome.FAILED);
        }
    }

    @Test
    void PG_불능_상황이_반복되면_서킷이_열리고_그_뒤엔_원본_호출_없이_UNKNOWN을_즉시_반환한다() throws IOException {
        // forceTimeout=true: MockPgServer가 응답을 주지 않아 MockPgClient의 5s 읽기
        // 타임아웃이 걸린다 — ResourceAccessException -> MockPgUnavailableException.
        configureMockPg(0, 0.0, null, true);
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        ResilientMockPgGateway gateway = newGateway(config);

        // 처음 2번은 실제로 5s 타임아웃을 겪으며 UNKNOWN(timedOut)을 반환하고, 그 2번으로
        // 슬라이딩 윈도우가 채워져 실패율 100%가 임계값(50%)을 넘는다 — 서킷이 OPEN된다.
        for (int i = 0; i < 2; i++) {
            MockPgResult result = gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
            assertThat(result.outcome()).isEqualTo(MockPgOutcome.TIMEOUT);
        }

        // gateway 내부 CircuitBreaker 상태를 직접 관찰할 수는 없으므로, 서킷이 열린 뒤에는
        // 원본 호출(5s 타임아웃) 없이 즉시 돌아온다는 사실 자체로 OPEN을 간접 증명한다.
        long start = System.nanoTime();
        MockPgResult result =
                gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(MockPgOutcome.TIMEOUT);
        // 원본 호출까지 갔다면 5000ms 근처가 나온다 — CallNotPermittedException으로
        // 즉시 끝났다면 수 ms 안에 반환된다. 500ms를 기준으로 삼아도 충분히 구분된다.
        assertThat(elapsedMs).isLessThan(500);
    }

    private static CircuitBreakerConfig defaultConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build();
    }

    private void configureMockPg(long delayMs, double failureRate, String forcedErrorCode, boolean forceTimeout)
            throws IOException {
        // MockPgClient는 결제 요청 전용이라 /pg/_config를 직접 못 친다 — 순수 HTTP로 설정한다.
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String body = String.format(
                java.util.Locale.ROOT,
                "{\"delayMs\":%d,\"failureRate\":%s,\"forcedErrorCode\":%s,\"forceTimeout\":%s}",
                delayMs,
                failureRate,
                forcedErrorCode == null ? "null" : "\"" + forcedErrorCode + "\"",
                forceTimeout);
        try {
            http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl + "/pg/_config"))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
