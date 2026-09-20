package org.example.cs_study.mockpg;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Docker/Testcontainers 불필요 — 순수 JDK HttpServer 기반이라 플레인 JUnit으로 검증한다.
 * 1.5(런타임 주입 가능한 지연/실패율/에러코드/타임아웃)와 1.6(Mock PG 자체 멱등성)을 커버한다.
 */
class MockPgServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static int port;
    private static MockPgServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new MockPgServer();
        port = server.start(0); // OS가 빈 포트를 골라줌
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    @AfterEach
    void resetConfig() throws Exception {
        postJson("/pg/_config", Map.of("reset", true));
    }

    @Test
    void 기본_설정에서는_항상_즉시_승인한다() throws Exception {
        Map<String, Object> response = requestPayment(UUID.randomUUID().toString());

        assertThat(response.get("status")).isEqualTo("APPROVED");
        assertThat(response.get("transactionId")).isNotNull();
    }

    @Test
    void forcedErrorCode를_설정하면_항상_그_에러코드로_거절한다() throws Exception {
        postJson("/pg/_config", Map.of("forcedErrorCode", "CARD_DECLINED"));

        Map<String, Object> response = requestPayment(UUID.randomUUID().toString());

        assertThat(response.get("status")).isEqualTo("FAILED");
        assertThat(response.get("errorCode")).isEqualTo("CARD_DECLINED");
    }

    @Test
    void failureRate가_1이면_전부_실패한다() throws Exception {
        postJson("/pg/_config", Map.of("failureRate", 1.0));

        Map<String, Object> response = requestPayment(UUID.randomUUID().toString());

        assertThat(response.get("status")).isEqualTo("FAILED");
    }

    @Test
    void delayMs만큼_응답이_지연된다() throws Exception {
        postJson("/pg/_config", Map.of("delayMs", 300));

        long start = System.nanoTime();
        requestPayment(UUID.randomUUID().toString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(280); // 스케줄링 오차 여유
    }

    @Test
    void 동일_idempotencyKey로_동시_요청해도_승인은_한_번만_난다() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        int concurrency = 50;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Callable<Map<String, Object>>> tasks = IntStream.range(0, concurrency)
                    .<Callable<Map<String, Object>>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await();
                        return requestPayment(idempotencyKey);
                    })
                    .collect(Collectors.toList());

            List<Future<Map<String, Object>>> futures = new java.util.ArrayList<>();
            for (Callable<Map<String, Object>> task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            Set<Object> transactionIds = new java.util.HashSet<>();
            for (Future<Map<String, Object>> future : futures) {
                Map<String, Object> response = future.get(10, TimeUnit.SECONDS);
                assertThat(response.get("status")).isEqualTo("APPROVED");
                transactionIds.add(response.get("transactionId"));
            }

            // 동시에 50번 요청해도 같은 승인 결과 하나만 존재해야 한다 (이중 승인 없음).
            assertThat(transactionIds).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void forceTimeout이면_설정된_대기시간만큼_응답이_늦어진다() throws Exception {
        MockPgServer shortTimeoutServer = new MockPgServer(500);
        int shortPort = shortTimeoutServer.start(0);
        try {
            postJson(shortPort, "/pg/_config", Map.of("forceTimeout", true));

            long start = System.nanoTime();
            Map<String, Object> response = requestPayment(shortPort, UUID.randomUUID().toString());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isGreaterThanOrEqualTo(480);
            // forceTimeout이어도 결국은 응답한다 (설정된 만큼 늦게) — 실제 클라이언트는
            // 자신의 타임아웃이 더 짧으면 이 응답을 못 받고 UNKNOWN 처리하게 된다 (3.4).
            assertThat(response.get("status")).isIn("APPROVED", "FAILED");
        } finally {
            shortTimeoutServer.stop();
        }
    }

    private static Map<String, Object> requestPayment(String idempotencyKey) throws Exception {
        return requestPayment(port, idempotencyKey);
    }

    private static Map<String, Object> requestPayment(int targetPort, String idempotencyKey) throws Exception {
        Map<String, Object> body = Map.of(
                "idempotencyKey", idempotencyKey,
                "amount", "1000.0000",
                "currency", "KRW");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + targetPort + "/pg/payments"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                .build();
        HttpResponse<byte[]> response = CLIENT.send(request, BodyHandlers.ofByteArray());
        return JSON.readValue(response.body(), Map.class);
    }

    private static void postJson(String path, Map<String, Object> body) throws Exception {
        postJson(port, path, body);
    }

    private static void postJson(int targetPort, String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + targetPort + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                .build();
        CLIENT.send(request, BodyHandlers.discarding());
    }
}
