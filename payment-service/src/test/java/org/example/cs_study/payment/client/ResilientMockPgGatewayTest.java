package org.example.cs_study.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.example.cs_study.mockpg.MockPgServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 3.2/3.3 — {@link ResilientMockPgGateway}가 Retry+CircuitBreaker를 3.1이 결정한 순서
 * (Retry 바깥 → CircuitBreaker 안쪽)로 실제로 올바르게 감싸는지 증명한다. Spring 컨텍스트도
 * Docker도 필요 없다 — {@link MockPgServer}를 인프로세스로 띄우고(1.5,
 * {@code PaymentIdempotencyConcurrencyTest}와 같은 패턴), 순수 Resilience4j core API로
 * CircuitBreaker/Retry를 직접 구성한다.
 */
class ResilientMockPgGatewayTest {

    /** CircuitBreaker 동작만 보는 테스트에서 Retry 변수를 없애기 위한 사실상 무재시도 설정. */
    private static final RetryConfig NO_RETRY =
            RetryConfig.custom().maxAttempts(1).build();

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

    private ResilientMockPgGateway newGateway(CircuitBreakerConfig cbConfig) {
        return newGateway(cbConfig, NO_RETRY, Duration.ofSeconds(5));
    }

    private ResilientMockPgGateway newGateway(CircuitBreakerConfig cbConfig, RetryConfig retryConfig) {
        return newGateway(cbConfig, retryConfig, Duration.ofSeconds(5));
    }

    private ResilientMockPgGateway newGateway(CircuitBreakerConfig cbConfig, RetryConfig retryConfig, Duration readTimeout) {
        MockPgClient client = new MockPgClient(baseUrl, readTimeout);
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        return new ResilientMockPgGateway(client, cbRegistry, retryRegistry);
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

    @Test
    void 지속적인_PG_불능이면_지수_백오프로_재시도하다_결국_UNKNOWN으로_포기한다() throws IOException {
        // 클라이언트 읽기 타임아웃을 100ms로 짧게 줘서(3.4 이전이라 MockPgClient 자체
        // 타임아웃이 없으므로 이 생성자로 대체) 테스트가 5s x 3회를 기다리지 않게 한다.
        configureMockPg(0, 0.0, null, true);
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .retryExceptions(MockPgUnavailableException.class)
                .build();
        // 3번의 시도 안에는 서킷이 절대 열리지 않도록 CB 임계치를 넉넉히 잡아, 이 테스트가
        // 순수하게 Retry의 지수 백오프만 관찰하게 한다(서킷 개입은 별도 테스트에서 다룬다).
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        ResilientMockPgGateway gateway = newGateway(cbConfig, retryConfig, Duration.ofMillis(100));

        long start = System.nanoTime();
        MockPgResult result =
                gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(MockPgOutcome.TIMEOUT);
        // 시도 3회(각 ~100ms) + 지수 백오프 2회(200ms, 400ms) = 900ms 근처. 스케줄링 변동을
        // 감안해 700ms 이상이면 백오프가 실제로 두 번 걸렸다고 볼 수 있다.
        assertThat(elapsedMs).isBetween(700L, 5000L);
    }

    @Test
    void 재시도_도중_서킷이_열리면_남은_재시도는_즉시_실패로_끝나_백오프를_낭비하지_않는다() throws IOException {
        configureMockPg(0, 0.0, null, true);
        // maxAttempts를 넉넉히(5) 줘서 "서킷이 안 열렸다면 더 재시도했을 상황"을 만든다.
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(5)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(150), 2.0))
                .retryExceptions(MockPgUnavailableException.class)
                .build();
        // 2번 만에 슬라이딩 윈도우가 차서 서킷이 열리도록 임계치를 낮게 잡는다(3.1 실험 2와 동일).
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        ResilientMockPgGateway gateway = newGateway(cbConfig, retryConfig, Duration.ofMillis(100));

        long start = System.nanoTime();
        MockPgResult result =
                gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(MockPgOutcome.TIMEOUT);
        // 실제 흐름: 시도1(~100ms, 실패 1/2) -> 백오프 150ms -> 시도2(~100ms, 실패 2/2, 서킷
        // OPEN) -> 백오프 300ms -> 시도3은 CallNotPermittedException으로 즉시 끝난다(원본
        // 호출 없음, retryExceptions에 없어 재시도도 안 함). 합쳐서 650ms 안팎이어야 한다 —
        // 만약 CallNotPermittedException도 재시도됐다면 백오프 600ms+1200ms가 더 붙어
        // 2400ms를 넘겼을 것이다.
        assertThat(elapsedMs).isBetween(400L, 1500L);
    }

    @Test
    void 같은_idempotencyKey로_재시도해도_PG는_한_번만_처리하고_같은_결과를_재현한다() throws IOException {
        // delayMs(300ms) > 클라이언트 읽기 타임아웃(100ms): 첫 시도는 반드시 클라이언트
        // 타임아웃으로 실패한다. 하지만 서버 쪽 처리는 취소되지 않고 백그라운드에서 계속
        // 진행된다(1.6 멱등성 캐시) — 백오프(250ms) 뒤의 두 번째 시도가 도착할 때(300ms
        // 경과 시점 이후)는 이미 완료된 결과를 즉시 재현받는다.
        //
        // 만약 재시도마다 idempotencyKey를 새로 생성하는 버그가 있었다면, 매 시도가 처음부터
        // 300ms 지연을 다시 겪어 결국 3번 다 타임아웃으로 소진돼 TIMEOUT이 됐을 것이다 —
        // 여기서 APPROVED가 나온다는 사실 자체가 재시도 전체에서 같은 키를 재사용한다는
        // 증거다.
        configureMockPg(300, 0.0, null, false);
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(250))
                .retryExceptions(MockPgUnavailableException.class)
                .build();
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        ResilientMockPgGateway gateway = newGateway(cbConfig, retryConfig, Duration.ofMillis(100));

        MockPgResult result =
                gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");

        assertThat(result.outcome()).isEqualTo(MockPgOutcome.APPROVED);
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
