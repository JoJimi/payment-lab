package org.example.cs_study.mockpg;

import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 1.5 — 별도 프로세스로 기동하는 Mock PG 서버.
 *
 * <p>실제 PG처럼 다음을 런타임에 주입 가능하게 한다: 응답 지연(ms), 실패율(%), 특정
 * 에러코드 강제, 타임아웃 유발. {@code POST /pg/_config}로 갱신하고 {@code GET /pg/_config}로
 * 확인한다. 3단계 Resilience4j 학습 품질이 이 Mock의 품질에 그대로 좌우되므로(로드맵 1.5),
 * 설정 없이는 항상 즉시 승인하는 결정론적 기본값을 유지한다.
 *
 * <p>1.6 — Mock PG 자체의 멱등성: 동일 {@code idempotencyKey}는 최초 1회만 실제로 처리하고,
 * 동시/후속 요청은 그 결과가 나올 때까지 기다렸다가 동일한 응답을 재현한다. 실제 PG사도
 * 멱등 키를 받으므로, 재시도 시 이중 승인이 나지 않는 걸 확인하려면 Mock도 멱등해야 한다.
 *
 * <p>순수 JDK {@link HttpServer} 기반이라 Spring 컴포넌트가 하나도 없다 — 메인 애플리케이션의
 * 컴포넌트 스캔에 절대 딸려 들어오지 않고, 진짜 독립 프로세스로만 기동된다.
 */
public final class MockPgServer {

    private static final int DEFAULT_PORT = 8090;
    // forceTimeout일 때 대기하는 시간. 클라이언트 쪽 타임아웃(3단계 TimeLimiter 3s 등)보다
    // 충분히 길게 잡아 "응답이 늦어 클라이언트가 먼저 포기하는" 실제 타임아웃 상황을 재현한다.
    // 테스트에서는 생성자로 짧게 주입해 30초씩 기다리지 않는다.
    private static final long DEFAULT_FORCE_TIMEOUT_WAIT_MS = 30_000;

    private final long forceTimeoutWaitMs;
    private final MockPgConfig config = new MockPgConfig();
    private final ConcurrentHashMap<String, CompletableFuture<PgPaymentResult>> idempotencyCache =
            new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile HttpServer server;

    public MockPgServer() {
        this(DEFAULT_FORCE_TIMEOUT_WAIT_MS);
    }

    MockPgServer(long forceTimeoutWaitMs) {
        this.forceTimeoutWaitMs = forceTimeoutWaitMs;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        MockPgServer server = new MockPgServer();
        int boundPort = server.start(port);
        System.out.printf("Mock PG server listening on :%d (POST /pg/payments, GET|POST /pg/_config)%n", boundPort);
    }

    /**
     * 실제로 바인딩된 포트를 반환한다 (0을 넘기면 OS가 빈 포트를 골라준다).
     * public인 이유: 다른 패키지의 통합 테스트(1.9, 1.10)가 실제 Mock PG 없이도
     * 인프로세스로 기동해 재사용할 수 있어야 하기 때문.
     */
    public int start(int port) throws IOException {
        HttpServer newServer = HttpServer.create(new InetSocketAddress(port), 0);
        newServer.createContext("/pg/payments", guarded(this::handlePayment));
        newServer.createContext("/pg/_config", guarded(this::handleConfig));
        newServer.setExecutor(workers);
        newServer.start();
        this.server = newServer;
        return newServer.getAddress().getPort();
    }

    /** 테스트 teardown에서 호출 — 소켓을 닫고 워커 스레드를 정리한다. */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        workers.shutdownNow();
    }

    private void handlePayment(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error("POST만 지원합니다"));
            return;
        }

        Map<?, ?> body = json.readValue(exchange.getRequestBody(), Map.class);
        Object rawKey = body.get("idempotencyKey");
        if (!(rawKey instanceof String idempotencyKey) || idempotencyKey.isBlank()) {
            sendJson(exchange, 400, error("idempotencyKey는 필수입니다"));
            return;
        }

        // computeIfAbsent 매핑 함수는 즉시 반환되는 supplyAsync 호출만 하므로 맵 락을 오래 잡지 않는다.
        CompletableFuture<PgPaymentResult> future =
                idempotencyCache.computeIfAbsent(idempotencyKey, key -> CompletableFuture.supplyAsync(this::process, workers));

        try {
            PgPaymentResult result = future.get(forceTimeoutWaitMs + 5_000, TimeUnit.MILLISECONDS);
            int status = "APPROVED".equals(result.status()) ? 200 : 402;
            sendJson(exchange, status, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 500, error("interrupted"));
        } catch (ExecutionException | TimeoutException e) {
            sendJson(exchange, 504, error("Mock PG 처리 실패: " + e.getMessage()));
        }
    }

    /** 설정에 따라 지연 후 승인/거절을 결정한다. idempotencyCache에 의해 키당 정확히 1번만 호출된다. */
    private PgPaymentResult process() {
        if (config.forceTimeout()) {
            sleep(forceTimeoutWaitMs);
        } else if (config.delayMs() > 0) {
            sleep(config.delayMs());
        }

        String forcedError = config.forcedErrorCode();
        if (forcedError != null) {
            return PgPaymentResult.failed(forcedError);
        }
        if (random.nextDouble() < config.failureRate()) {
            return PgPaymentResult.failed("PG_DECLINED");
        }
        return PgPaymentResult.approved("mockpg-" + UUID.randomUUID());
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            sendJson(exchange, 200, currentConfigAsMap());
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            Map<?, ?> body = json.readValue(exchange.getRequestBody(), Map.class);
            if (Boolean.TRUE.equals(body.get("reset"))) {
                config.reset();
            } else {
                config.update(
                        asLong(body.get("delayMs")),
                        asDouble(body.get("failureRate")),
                        (String) body.get("forcedErrorCode"),
                        (Boolean) body.get("forceTimeout"));
            }
            sendJson(exchange, 200, currentConfigAsMap());
            return;
        }
        sendJson(exchange, 405, error("GET/POST만 지원합니다"));
    }

    private Map<String, Object> currentConfigAsMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("delayMs", config.delayMs());
        map.put("failureRate", config.failureRate());
        map.put("forcedErrorCode", config.forcedErrorCode());
        map.put("forceTimeout", config.forceTimeout());
        return map;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 핸들러에서 던진 예외가 조용히 커넥션을 끊지 않고 500으로라도 응답하게 감싼다. */
    private HttpHandler guarded(HttpHandler delegate) {
        return exchange -> {
            try {
                delegate.handle(exchange);
            } catch (Exception e) {
                sendJson(exchange, 500, error("internal error: " + e.getMessage()));
            }
        };
    }
}
