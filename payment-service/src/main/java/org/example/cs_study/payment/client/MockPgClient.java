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
 * <p><b>3.4:</b> 읽기 타임아웃은 더 이상 이 클래스가 독자적으로 정하는 값이 아니다 —
 * {@code resilience4j.timelimiter.instances.mockPg.timeout-duration}과 같은 값을 공유해서
 * 쓴다(1단계 임시값 5초를 대체). {@link ResilientMockPgGateway}의 {@code TimeLimiter}가 이
 * 시간이 지나면 논리적으로 포기하고 {@code Future}를 취소하는데, 이 클래스가 쓰는 blocking
 * HTTP 클라이언트는 인터럽트에 응답하지 않아 실제 소켓은 더 오래 붙들려 있을 수 있다 — 그
 * "더 오래"의 상한을 TimeLimiter의 논리적 타임아웃과 맞춰, 최소한 둘이 크게 어긋나지 않게
 * 한다. 스레드가 실제로 격리되지 않은 채 남아있는 문제 자체는 3.5(Bulkhead)의 몫이다.
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

    // 테스트(Retry/CircuitBreaker/TimeLimiter 검증)는 이 생성자를 직접 호출해 readTimeout을
    // 짧게 준다 — 운영에서는 Spring이 두 @Value를 읽어 호출한다. 생성자가 하나뿐이라 Spring의
    // 생성자 선택 모호성 문제(CI에서 실제로 겪음, 이전 버전에서 생성자가 둘이었을 때)는
    // 애초에 없다.
    public MockPgClient(
            @Value("${mockpg.base-url:http://localhost:8090}") String baseUrl,
            @Value("${resilience4j.timelimiter.instances.mockPg.timeout-duration:3s}") Duration readTimeout) {
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
