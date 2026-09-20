package org.example.cs_study.payment;

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
 * 지금은 타임아웃이 나면 {@link MockPgResult#timedOut()}으로 변환해 UNKNOWN 상태로 이어지게만 한다.
 */
@Component
class MockPgClient {

    private final RestClient restClient;

    MockPgClient(@Value("${mockpg.base-url:http://localhost:8090}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    MockPgResult requestPayment(String idempotencyKey, BigDecimal amount, String currency) {
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
                            return MockPgResult.timedOut();
                        }
                        MockPgResponseBody body = res.bodyTo(MockPgResponseBody.class);
                        if (body == null || body.status() == null) {
                            return MockPgResult.timedOut();
                        }
                        return "APPROVED".equals(body.status())
                                ? MockPgResult.approved(body.transactionId())
                                : MockPgResult.failed(body.errorCode());
                    });
        } catch (ResourceAccessException e) {
            // 커넥션/읽기 타임아웃 — PG가 실제로 승인했는지 알 수 없다.
            return MockPgResult.timedOut();
        }
    }

    private record MockPgRequestBody(String idempotencyKey, BigDecimal amount, String currency) {
    }

    private record MockPgResponseBody(String transactionId, String status, String errorCode) {
    }
}
