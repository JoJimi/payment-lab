package org.example.cs_study.payment.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p><b>3.4 — TimeLimiter가 왜 가장 안쪽인가</b>: 3.1이 결정한 순서(Retry 바깥 →
 * CircuitBreaker → TimeLimiter 안쪽)대로 원본 호출에 가장 밀착시킨다 — 그래야 재시도
 * 한 번 한 번이 독립적인 시간 예산을 받는다(3.1 실험 3). {@code MockPgClient.requestPayment}는
 * 동기(blocking) 호출이라, {@code TimeLimiter.decorateFutureSupplier}가 요구하는
 * {@code Future} 계약을 만족시키려면 별도 스레드에 맡겨야 한다 — 이 클래스가 소유한
 * {@link #executor}가 그 자리다. 다만 이 스레드풀은 아직 진짜 "격리"가 아니다(무제한
 * {@code newCachedThreadPool}) — PG 호출 전용으로 크기를 제한하고 거부 정책을 두는 것은
 * 3.5(Bulkhead)의 몫이다. {@code TimeLimiter}가 시간 초과로 포기해도({@code cancelRunningFuture}
 * 기본값 true) blocking HTTP 클라이언트는 인터럽트에 응답하지 않아 소켓은 계속 붙들려
 * 있을 수 있다 — {@link MockPgClient}의 읽기 타임아웃을 TimeLimiter와 같은 값으로 맞춰 그
 * "붙들림"의 상한을 최소한 비슷하게 묶어뒀다.
 *
 * <p>{@code TimeLimiter}의 {@link TimeoutException}도 {@link MockPgUnavailableException}과
 * 같은 취급을 받는다 — PG가 시간 안에 응답하지 못했다는 것도 "승인/거절을 확정할 수 없다"는
 * 뜻이라 재시도 대상이고(서킷 상태 확인 포함), 최종적으로도 UNKNOWN(timedOut)으로 번역된다.
 */
@Component
public class ResilientMockPgGateway {

    private final MockPgClient mockPgClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ResilientMockPgGateway(
            MockPgClient mockPgClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry) {
        this.mockPgClient = mockPgClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("mockPg");
        this.timeLimiter = timeLimiterRegistry.timeLimiter("mockPg");
        // application.yml의 retry-exceptions만으로는 부족하다(CodeRabbit 리뷰, PR #87) — 서킷을
        // 실제로 OPEN시킨 그 실패도 MockPgUnavailableException이라 재시도 대상으로 잡혀, 다음
        // 시도 전에 백오프를 한 번 더 기다린 뒤에야(그 시도에서 CallNotPermittedException을
        // 받고 나서야) 멈춘다. retryOnException에 서킷 상태를 직접 물어 OPEN이면 그 자리에서
        // 재시도를 접도록 덮어써, 이미 열린 서킷 앞에서 쓸모없는 백오프를 기다리지 않게 한다 —
        // YAML에 정의된 지수 백오프/지터/시도 횟수는 그대로 재사용한다. TimeoutException(3.4,
        // TimeLimiter가 던짐)도 MockPgUnavailableException과 같은 이유로 재시도 대상이다.
        // RetryConfig.from(base)는 base의 retryExceptions 클래스 목록도 그대로 복사한다 — 그
        // 목록과 retryOnException 프레디케이트는 AND가 아니라 OR로 합쳐지므로(resilience4j
        // PredicateCreator), retryExceptions를 명시적으로 비우지 않으면 "instanceof
        // MockPgUnavailableException"이 서킷 상태와 무관하게 항상 참이 되어 아래 프레디케이트가
        // 무력화된다.
        RetryConfig config = RetryConfig.from(retryRegistry.retry("mockPg").getRetryConfig())
                .retryExceptions()
                .retryOnException(t -> (t instanceof MockPgUnavailableException || t instanceof TimeoutException)
                        && circuitBreaker.getState() != CircuitBreaker.State.OPEN)
                .build();
        this.retry = retryRegistry.retry("mockPg-gateway", () -> config);
    }

    public MockPgResult requestPayment(String idempotencyKey, BigDecimal amount, String currency) {
        // TimeLimiterImpl.decorateFutureSupplier가 대기 중(future.get(timeout, unit))에
        // InterruptedException을 받으면, TimeoutException/ExecutionException과 달리 future를
        // 취소하지 않고 그대로 던진다(2.4.0 바이트코드로 확인, CodeRabbit 리뷰, PR #88) — 그
        // 자리에서 잡지 않으면 PG 호출은 백그라운드에서 계속 진행되는데 이 메서드는 이미
        // 끝나버려 그 결과를 아무도 반영하지 못한다. 마지막으로 제출한 future를 직접 들고
        // 있다가, InterruptedException을 받으면 그 future를 취소하고 인터럽트 상태를 복원한다.
        AtomicReference<Future<MockPgResult>> inFlight = new AtomicReference<>();
        Callable<MockPgResult> withTimeLimiter = TimeLimiter.decorateFutureSupplier(timeLimiter, () -> {
            Future<MockPgResult> future =
                    executor.submit(() -> mockPgClient.requestPayment(idempotencyKey, amount, currency));
            inFlight.set(future);
            return future;
        });
        Callable<MockPgResult> withCircuitBreaker = CircuitBreaker.decorateCallable(circuitBreaker, withTimeLimiter);
        Callable<MockPgResult> withRetry = Retry.decorateCallable(retry, withCircuitBreaker);
        try {
            return withRetry.call();
        } catch (MockPgUnavailableException | CallNotPermittedException | TimeoutException e) {
            return MockPgResult.timedOut();
        } catch (InterruptedException e) {
            Future<MockPgResult> future = inFlight.get();
            if (future != null) {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            // 결제가 실제로 승인됐는지 이 스레드는 더 이상 기다리지 않기로 한 것뿐이다 — PG가
            // 거절했다는 증거는 없으므로 FAILED가 아니라 UNKNOWN이다(위 클래스 Javadoc과 같은
            // 원칙). payment는 이미 PENDING으로 저장돼 있어(PaymentService.doRequestPayment),
            // 여기서 UNKNOWN을 돌려주지 않으면 PaymentService.applyResult에 영영 도달하지
            // 못하고 PENDING에 갇힌다.
            return MockPgResult.timedOut();
        } catch (Exception e) {
            // Callable 계약상 checked Exception이 선언돼 있을 뿐, 위 네 타입 외에는 원래
            // 나올 일이 없다 — 나오면 우리가 모르는 새로운 실패 모드이므로 조용히 삼키지 않는다.
            throw new IllegalStateException("Mock PG 호출 중 예상하지 못한 예외", e);
        }
    }

    /** 스프링 컨텍스트 종료 시 스레드가 새지 않도록 정리한다. */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
