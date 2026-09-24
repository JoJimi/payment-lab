package org.example.cs_study.payment.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * {@link MockPgClient}를 Retry+CircuitBreaker로 감싼 façade(3.2, 3.3). {@code PaymentService}는
 * 이 클래스를 통해서만 Mock PG를 호출한다 — {@link MockPgClient}를 직접 호출하면
 * 이 보호 없이 매번 원본 호출로 새는 실수를 막는다.
 *
 * <p>{@code application.yml}의 {@code resilience4j.circuitbreaker.instances.mockPg}/
 * {@code resilience4j.retry.instances.mockPg} 설정(0단계에서 미리 확정: CircuitBreaker의
 * {@code register-health-indicator: false} — 서킷 OPEN이 {@code /actuator/health}를 DOWN으로
 * 만들지 않게 해 5단계 readiness probe에서 외부 장애 때 파드가 빠지는 걸 막는다)을 그대로 쓴다.
 *
 * <p><b>서킷 OPEN을 왜 FAILED가 아니라 UNKNOWN(timedOut)으로 번역하는가</b>: 서킷이 열려
 * 원본 호출조차 시도하지 않았다는 것은 "PG가 거절했다"는 증거가 전혀 없다는 뜻이다 —
 * 결제가 실제로는 승인됐을 수도 있는데 FAILED로 잘못 단정하면 order-service가 보상
 * (재고 해제, 주문 취소)을 잘못 개시하게 된다. {@code PaymentService.applyResult}가 이미
 * TIMEOUT을 FAILED와 구분해 UNKNOWN으로 처리하는 원칙(1단계, 부록 A-1과 동일 선상)을
 * CircuitBreaker OPEN 상황에도 똑같이 적용한다.
 *
 * <p><b>3.3 — Retry가 왜 바깥이고, 왜 안전한가</b>: 3.1의 실험대로 Retry를 CircuitBreaker
 * 바깥에 둔다 — 그래야 매 재시도가 개별적으로 CircuitBreaker를 통과해 실제 실패율을 정확히
 * 반영한다. 서킷이 이미 열려 원본 호출조차 못 간 {@link CallNotPermittedException}은 YAML의
 * {@code retry-exceptions}에 없어 즉시 끝나지만, 그것만으로는 부족하다 — 서킷을 실제로 연
 * 그 실패 자체는 여전히 재시도 대상인 {@code MockPgUnavailableException}이라, 그 실패
 * 직후엔 백오프를 한 번 더 기다린 뒤에야(그다음 시도에서 비로소 서킷 OPEN을 만나고서야)
 * 멈춘다. 그래서 생성자에서 {@code retryOnException}을 서킷 상태까지 확인하도록 덮어써,
 * 서킷이 OPEN인 동안은 그 자리에서 재시도를 접는다(CodeRabbit 리뷰, PR #87). 재시도가
 * 안전한 이유는 이 메서드가 항상 같은 {@code idempotencyKey}를 파라미터로 받아 재시도
 * 전체에서 재사용하기 때문이다 — {@code MockPgServer}(1.6)가 같은 키를 최초 1회만 실제로
 * 처리하고 이후 요청엔 그 결과를 그대로 재현하므로, 몇 번을 재시도해도 이중 승인이 나지
 * 않는다.
 *
 * <p>TimeLimiter는 아직 감지 않는다 — 3.1이 결정한 순서(Retry 바깥 → CircuitBreaker →
 * TimeLimiter 안쪽)대로 3.4에서 이 클래스 가장 안쪽에 덧씌운다.
 */
@Component
public class ResilientMockPgGateway {

    private final MockPgClient mockPgClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientMockPgGateway(
            MockPgClient mockPgClient, CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.mockPgClient = mockPgClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("mockPg");
        // application.yml의 retry-exceptions만으로는 부족하다(CodeRabbit 리뷰, PR #87) — 서킷을
        // 실제로 OPEN시킨 그 실패도 MockPgUnavailableException이라 재시도 대상으로 잡혀, 다음
        // 시도 전에 백오프를 한 번 더 기다린 뒤에야(그 시도에서 CallNotPermittedException을
        // 받고 나서야) 멈춘다. retryOnException에 서킷 상태를 직접 물어 OPEN이면 그 자리에서
        // 재시도를 접도록 덮어써, 이미 열린 서킷 앞에서 쓸모없는 백오프를 기다리지 않게 한다 —
        // YAML에 정의된 지수 백오프/지터/시도 횟수는 그대로 재사용한다.
        // RetryConfig.from(base)는 base의 retryExceptions 클래스 목록도 그대로 복사한다 — 그
        // 목록과 retryOnException 프레디케이트는 AND가 아니라 OR로 합쳐지므로(resilience4j
        // PredicateCreator), retryExceptions를 명시적으로 비우지 않으면 "instanceof
        // MockPgUnavailableException"이 서킷 상태와 무관하게 항상 참이 되어 아래 프레디케이트가
        // 무력화된다.
        RetryConfig config = RetryConfig.from(retryRegistry.retry("mockPg").getRetryConfig())
                .retryExceptions()
                .retryOnException(t -> t instanceof MockPgUnavailableException
                        && circuitBreaker.getState() != CircuitBreaker.State.OPEN)
                .build();
        this.retry = retryRegistry.retry("mockPg-gateway", () -> config);
    }

    public MockPgResult requestPayment(String idempotencyKey, BigDecimal amount, String currency) {
        Supplier<MockPgResult> withCircuitBreaker = CircuitBreaker.decorateSupplier(
                circuitBreaker, () -> mockPgClient.requestPayment(idempotencyKey, amount, currency));
        Supplier<MockPgResult> withRetry = Retry.decorateSupplier(retry, withCircuitBreaker);
        try {
            return withRetry.get();
        } catch (MockPgUnavailableException | CallNotPermittedException e) {
            return MockPgResult.timedOut();
        }
    }
}
