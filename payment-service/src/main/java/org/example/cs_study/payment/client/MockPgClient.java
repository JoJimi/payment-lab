package org.example.cs_study.payment.client;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * {@code org.example.cs_study.mockpg.MockPgServer}(별도 프로세스, 1.5)를 호출하는 클라이언트.
 *
 * <p>읽기 타임아웃은 1단계 임시값(5s)이다. 3단계에서 Resilience4j
 * {@code TimeLimiter}(3.4)로 교체하고, 데코레이터 적용 순서(3.1)에 맞춰 재구성할 예정.
 *
 * <p><b>3.2:</b> PG가 승인/거절을 확정하지 못하는 상황(5xx, 타임아웃, 파싱 불가)은
 * {@link MockPgResult#timedOut()}을 반환하는 대신 {@link MockPgUnavailableException}을
 * 던진다 — {@link ResilientMockPgGateway}의 CircuitBreaker가 "기술적 실패"만 골라
 * 반응하려면 정상 반환(승인/거절)과 구분되는 예외가 필요하다. 이 클래스를 직접 호출하는
 * 코드는 없어야 한다({@link ResilientMockPgGateway}를 통해서만 호출) — 2.10 Semgrep 룰이
 * {@code KafkaTemplate} 직접 호출을 막는 것과 같은 이유로, 향후 필요하면 이 경로도
 * 기계적으로 강제할 수 있다.
 */
@Component
public class MockPgClient {

    private final RestClient restClient;

    public MockPgClient(@Value("${mockpg.base-url:http://localhost:8090}") String baseUrl) {
        this(baseUrl, Duration.ofSeconds(5));
    }

    /** 테스트 전용 — Retry/TimeLimiter 검증에서 5s 기본값 대신 짧은 읽기 타임아웃을 주입한다. */
    MockPgClient(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public MockPgResult requestPayment(String idempotencyKey, BigDecimal amount, String currency) {
        try {
            return restClient
                    .post()
                    .uri("/pg/payments")
                    .body(new MockPgRequestBody(idempotencyKey, amount, currency))
                    .exchange((req, res) -> {
                        // 5xx(처리 실패/내부 타임아웃)는 승인 여부를 알 수 없다 — FAILED로 단정하지 않는다.
                        // MockPgServer.handlePayment는 5xx일 때 {"error": ...}만 주고 status 필드가
                        // 없으므로, 억지로 body를 읽으면 body.status()가 null이 되어 FAILED로 잘못
                        // 확정되거나(경로 지침 위반) null 자체에서 NPE가 난다.
                        if (res.getStatusCode().is5xxServerError()) {
                            throw new MockPgUnavailableException("Mock PG 5xx 응답: " + res.getStatusCode());
                        }
                        MockPgResponseBody body = res.bodyTo(MockPgResponseBody.class);
                        if (body == null || body.status() == null) {
                            throw new MockPgUnavailableException("Mock PG 응답을 파싱할 수 없습니다");
                        }
                        return "APPROVED".equals(body.status())
                                ? MockPgResult.approved(body.transactionId())
                                : MockPgResult.failed(body.errorCode());
                    });
        } catch (ResourceAccessException e) {
            // 커넥션/읽기 타임아웃 — PG가 실제로 승인했는지 알 수 없다.
            throw new MockPgUnavailableException("Mock PG 커넥션/읽기 타임아웃", e);
        }
    }

    private record MockPgRequestBody(String idempotencyKey, BigDecimal amount, String currency) {
    }

    private record MockPgResponseBody(String transactionId, String status, String errorCode) {
    }
}
