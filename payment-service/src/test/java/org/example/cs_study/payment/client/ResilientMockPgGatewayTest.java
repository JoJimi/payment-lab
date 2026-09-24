package org.example.cs_study.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.example.cs_study.mockpg.MockPgServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 3.2/3.3/3.4/3.5 — {@link ResilientMockPgGateway}가 Retry+CircuitBreaker+TimeLimiter+Bulkhead를
 * 3.1이 결정한 순서(Retry 바깥 → CircuitBreaker → TimeLimiter → Bulkhead 안쪽)로 실제로
 * 올바르게 감싸는지 증명한다. Spring 컨텍스트도 Docker도 필요 없다 — {@link MockPgServer}를
 * 인프로세스로 띄우고(1.5, {@code PaymentIdempotencyConcurrencyTest}와 같은 패턴), 순수
 * Resilience4j core API로 CircuitBreaker/Retry/TimeLimiter/ThreadPoolBulkhead를 직접
 * 구성한다.
 */
class ResilientMockPgGatewayTest {

    /** CircuitBreaker 동작만 보는 테스트에서 Retry 변수를 없애기 위한 사실상 무재시도 설정. */
    private static final RetryConfig NO_RETRY =
            RetryConfig.custom().maxAttempts(1).build();

    /** TimeLimiter 자체를 검증하는 테스트가 아니면 절대 먼저 끊기지 않을 만큼 넉넉한 제한. */
    private static final TimeLimiterConfig GENEROUS_TIME_LIMIT =
            TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(10)).build();

    /** Bulkhead 자체를 검증하는 테스트가 아니면 절대 거부당하지 않을 만큼 넉넉한 용량. */
    private static final ThreadPoolBulkheadConfig GENEROUS_BULKHEAD = ThreadPoolBulkheadConfig.custom()
            .coreThreadPoolSize(10)
            .maxThreadPoolSize(10)
            .queueCapacity(50)
            .build();

    private static MockPgServer mockPgServer;
    private static String baseUrl;

    // 각 테스트가 newGateway로 만든 ThreadPoolBulkhead(AutoCloseable, 자체 스레드풀을 소유)를
    // 추적해뒀다가 끝나면 close()한다 — Spring 밖에서 직접 생성하므로 @PreDestroy가 실행되지
    // 않아, 안 하면 매 테스트가 새 스레드풀을 남기고 끝난다(CodeRabbit 리뷰, PR #88 — 3.4
    // 시점엔 수작업 cached thread pool이었지만 3.5가 ThreadPoolBulkhead로 대체하면서 같은
    // 문제가 이 객체로 옮겨왔다).
    private final List<ThreadPoolBulkhead> createdBulkheads = new ArrayList<>();

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

    @AfterEach
    void closeBulkheads() throws Exception {
        for (ThreadPoolBulkhead bulkhead : createdBulkheads) {
            bulkhead.close();
        }
        createdBulkheads.clear();
    }

    private ResilientMockPgGateway newGateway(CircuitBreakerConfig cbConfig) {
        return newGateway(cbConfig, NO_RETRY, Duration.ofSeconds(5));
    }

    private ResilientMockPgGateway newGateway(CircuitBreakerConfig cbConfig, RetryConfig retryConfig) {
        return newGateway(cbConfig, retryConfig, Duration.ofSeconds(5));
    }

    private ResilientMockPgGateway newGateway(CircuitBreakerConfig cbConfig, RetryConfig retryConfig, Duration readTimeout) {
        return newGateway(cbConfig, retryConfig, readTimeout, GENEROUS_TIME_LIMIT);
    }

    private ResilientMockPgGateway newGateway(
            CircuitBreakerConfig cbConfig, RetryConfig retryConfig, Duration readTimeout, TimeLimiterConfig tlConfig) {
        return newGateway(cbConfig, retryConfig, readTimeout, tlConfig, GENEROUS_BULKHEAD);
    }

    private ResilientMockPgGateway newGateway(
            CircuitBreakerConfig cbConfig,
            RetryConfig retryConfig,
            Duration readTimeout,
            TimeLimiterConfig tlConfig,
            ThreadPoolBulkheadConfig bulkheadConfig) {
        MockPgClient client = new MockPgClient(baseUrl, readTimeout);
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.of(tlConfig);
        ThreadPoolBulkheadRegistry bulkheadRegistry = ThreadPoolBulkheadRegistry.of(bulkheadConfig);
        // ResilientMockPgGateway의 생성자도 bulkheadRegistry.bulkhead("mockPg")를 호출한다 —
        // 레지스트리는 이름으로 캐싱하므로 여기서 먼저 꺼내둬도 같은 인스턴스를 돌려받는다.
        createdBulkheads.add(bulkheadRegistry.bulkhead("mockPg"));
        return new ResilientMockPgGateway(client, cbRegistry, retryRegistry, tlRegistry, bulkheadRegistry);
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
    void 서킷을_연_실패_뒤에는_백오프_없이_그_자리에서_재시도를_멈춘다() throws IOException {
        configureMockPg(0, 0.0, null, true);
        // maxAttempts를 넉넉히(5) 줘서 "서킷 상태를 안 봤다면 더 재시도했을 상황"을 만든다 —
        // ResilientMockPgGateway는 이 retryConfig의 backoff/maxAttempts는 그대로 쓰지만,
        // 예외 프레디케이트는 생성자에서 서킷 상태를 확인하도록 자체적으로 덮어쓴다.
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
        // 실제 흐름: 시도1(~100ms, 실패 1/2, 서킷 아직 CLOSED) -> 백오프 150ms -> 시도2(~100ms,
        // 실패 2/2 — 이 실패 자체가 서킷을 OPEN으로 만든다) -> 프레디케이트가 그 자리에서
        // "서킷이 이미 OPEN"임을 확인하고 3번째 시도(와 그 앞의 백오프 300ms)를 아예 시작하지
        // 않는다. maxAttempts=5를 줬어도 실제로는 딱 2번만 시도된다 — 합쳐서 350ms 안팎이어야
        // 한다. 서킷 상태를 보지 않았다면(수정 전) 3번째 시도까지 가면서(원본 호출은 안 가더라도
        // CallNotPermittedException을 받기 전에 300ms 백오프를 한 번 더 날려 650ms를 넘겼다.
        assertThat(elapsedMs).isBetween(200L, 600L);
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

    @Test
    void PG가_응답은_하지만_느리면_TimeLimiter가_클라이언트_타임아웃보다_먼저_끊는다() throws IOException {
        // delayMs=1000: PG는 정상적으로 응답하지만 1초가 걸린다(장애가 아니라 그냥 느림) —
        // forceTimeout이 아니므로 클라이언트 읽기 타임아웃(5초, 넉넉히 큼)은 이 테스트에서
        // 절대 먼저 끊지 않는다. TimeLimiter만 짧게(200ms) 잡아, 실제로 끊는 주체가
        // MockPgClient의 소켓 타임아웃이 아니라 TimeLimiter라는 걸 증명한다.
        configureMockPg(1000, 0.0, null, false);
        TimeLimiterConfig shortTimeLimit =
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(200)).build();
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        ResilientMockPgGateway gateway =
                newGateway(cbConfig, NO_RETRY, Duration.ofSeconds(5), shortTimeLimit);

        long start = System.nanoTime();
        MockPgResult result =
                gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(MockPgOutcome.TIMEOUT);
        // TimeLimiter가 끊었다면 200ms 근처(넉넉히 잡아도 1초 미만)에 돌아온다. 만약
        // TimeLimiter가 배선되지 않아 클라이언트의 5초 소켓 타임아웃이나 PG의 1초 처리
        // 완료를 기다렸다면 1000ms를 훌쩍 넘겼을 것이다.
        assertThat(elapsedMs).isLessThan(1000L);
    }

    @Test
    void 동시_PG_호출이_스레드풀_용량을_넘으면_초과분은_BulkheadFullException으로_즉시_거부된다() throws Exception {
        // PG 응답을 300ms 지연시킨다(정상 처리, forceTimeout 아님) — "처리 중"인 상태를
        // 인위적으로 오래 유지해 동시 요청이 겹치게 만든다. core=1/max=1/queueCapacity=1이면
        // 동시에 받아줄 수 있는 요청은 딱 2건(실행 중 1 + 대기 1)뿐이다. 3번째가 동시에
        // 들어오면 스레드/큐 어디에도 못 들어가고 그 자리에서 BulkheadFullException으로
        // 거부된다(NO_RETRY라 재시도로 구제되지도 않는다).
        configureMockPg(300, 0.0, null, false);
        ThreadPoolBulkheadConfig tightBulkhead = ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(1)
                .maxThreadPoolSize(1)
                .queueCapacity(1)
                .build();
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        ResilientMockPgGateway gateway =
                newGateway(cbConfig, NO_RETRY, Duration.ofSeconds(5), GENEROUS_TIME_LIMIT, tightBulkhead);

        int concurrentRequests = 3;
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<MockPgResult>> futures = IntStream.range(0, concurrentRequests)
                    .mapToObj(i -> callers.submit(() -> {
                        startLatch.await();
                        return gateway.requestPayment(
                                UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
                    }))
                    .collect(Collectors.toList());
            startLatch.countDown();

            List<MockPgOutcome> outcomes = new ArrayList<>();
            for (Future<MockPgResult> future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS).outcome());
            }

            // 용량(2)을 넘는 1건만 거부되고, 나머지 2건은 (하나는 즉시, 하나는 큐에서 잠깐
            // 기다렸다가) 정상적으로 PG까지 도달해 승인된다.
            assertThat(outcomes)
                    .containsExactlyInAnyOrder(MockPgOutcome.APPROVED, MockPgOutcome.APPROVED, MockPgOutcome.TIMEOUT);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void 대기_중_스레드가_인터럽트되면_PG_작업을_취소하고_인터럽트_상태를_보존한_채_UNKNOWN을_반환한다() throws Exception {
        // PG 응답을 2초 지연시켜 충분히 오래 future.get(...)으로 블로킹 대기 중인 상태를
        // 만든다. TimeLimiter는 넉넉하게(5s) 잡아 이 테스트에서 절대 먼저 끊지 않게 한다 —
        // 순수하게 인터럽트 자체의 효과만 본다.
        configureMockPg(2000, 0.0, null, false);
        TimeLimiterConfig generousTimeLimit =
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build();
        ResilientMockPgGateway gateway =
                newGateway(defaultConfig(), NO_RETRY, Duration.ofSeconds(5), generousTimeLimit);

        AtomicReference<MockPgResult> resultRef = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            started.countDown();
            MockPgResult result =
                    gateway.requestPayment(UUID.randomUUID().toString(), new BigDecimal("1000.0000"), "KRW");
            resultRef.set(result);
            // 여기서 여전히 인터럽트 상태가 살아있어야 한다 — 삼키지 않고 복원했다는 증거다.
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        started.await();
        Thread.sleep(100); // future.get(...) 블로킹 대기 지점에 확실히 들어간 뒤에 인터럽트한다.

        long start = System.nanoTime();
        caller.interrupt();
        caller.join(5000);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(caller.isAlive()).isFalse();
        assertThat(resultRef.get().outcome()).isEqualTo(MockPgOutcome.TIMEOUT);
        assertThat(interruptedAfterReturn.get()).isTrue();
        // 2초 지연을 다 기다리지 않고 인터럽트 직후 곧바로 돌아왔는지 확인한다.
        assertThat(elapsedMs).isLessThan(1000L);
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
