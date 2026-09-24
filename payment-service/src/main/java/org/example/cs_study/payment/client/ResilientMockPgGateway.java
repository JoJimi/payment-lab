package org.example.cs_study.payment.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
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
 * 반영하고, 서킷이 열린 뒤엔 {@code retryExceptions}에 {@link CallNotPermittedException}이
 * 없어(즉 재시도 대상이 아니어서) 즉시 끝난다. 재시도가 안전한 이유는 이 메서드가 항상
 * 같은 {@code idempotencyKey}를 파라미터로 받아 재시도 전체에서 재사용하기 때문이다 —
 * {@code MockPgServer}(1.6)가 같은 키를 최초 1회만 실제로 처리하고 이후 요청엔 그 결과를
 * 그대로 재현하므로, 몇 번을 재시도해도 이중 승인이 나지 않는다.
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
        this.retry = retryRegistry.retry("mockPg");
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
