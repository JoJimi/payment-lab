package org.example.cs_study.payment.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 로드맵 3.1 — Retry/CircuitBreaker/TimeLimiter 적용 순서가 실제로 동작을 바꾼다는 것을
 * 순수 단위 테스트(Spring 컨텍스트·Docker·네트워크 불필요)로 증명한다. Mock PG를 향한
 * 실제 호출 배선은 3.2~3.6에서 각 데코레이터를 하나씩 튜닝하며 이 순서 위에 쌓는다 —
 * 이 테스트가 그 순서를 고정하는 근거다.
 *
 * <p><b>결론: Retry(가장 바깥) → CircuitBreaker → TimeLimiter(가장 안쪽, 실제 호출에 밀착)</b>.
 * 근거는 아래 두 실험에 있다.
 */
class DecoratorOrderExperimentTest {

    /**
     * <b>실험 1 — CircuitBreaker를 Retry 밖에 두면 서킷이 실제 장애율을 못 본다.</b>
     *
     * <p>원본 호출이 "실패, 실패, 성공" 패턴으로 매번 3번째 시도에서만 성공한다고 하자
     * (Mock PG가 간헐적으로만 응답하는 상황을 흉내낸다). Retry(maxAttempts=3)가 이걸 전부
     * 가려버리므로, 사용자 입장에서는 매 논리적 호출이 항상 성공한다.
     *
     * <p>{@code CircuitBreaker(Retry(call))}로 구성하면, CircuitBreaker는 "재시도까지 끝낸
     * 최종 결과"만 관찰한다 — 즉 원본 호출의 2/3이 실패하고 있어도 CircuitBreaker의 슬라이딩
     * 윈도우에는 오직 성공만 쌓인다. 서킷은 절대 열리지 않는다 — 실제로는 다운스트림이 매우
     * 불안정한데도 이 사실이 CircuitBreaker에게 완전히 숨겨진다.
     */
    @Test
    void CircuitBreaker가_Retry_밖에_있으면_재시도로_가려진_실패를_서킷이_못_본다() {
        AtomicInteger rawCallCount = new AtomicInteger();
        Supplier<String> flakyRawCall = () -> {
            int n = rawCallCount.incrementAndGet();
            // n=1,2 실패 / n=3 성공, n=4,5 실패 / n=6 성공 ... 반복
            if (n % 3 != 0) {
                throw new RuntimeException("transient failure #" + n);
            }
            return "OK";
        };

        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "mockPg-exp1",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .build());
        Retry retry = Retry.of(
                "mockPg-exp1", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build());

        // CircuitBreaker(바깥) → Retry(안쪽) → 원본 호출. 안티패턴 구성이다.
        Supplier<String> decorated =
                CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, flakyRawCall));

        for (int i = 0; i < 5; i++) {
            assertThat(decorated.get()).isEqualTo("OK");
        }

        // 원본 호출은 15번(5회 논리 호출 x 3회 시도) 일어났고 그중 10번이 실패했다 — 하지만
        // CircuitBreaker는 이 실패를 전혀 못 본다. 5번의 "논리적 호출"이 전부 성공으로만
        // 기록돼 서킷은 CLOSED에 머문다.
        assertThat(rawCallCount.get()).isEqualTo(15);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    /**
     * <b>실험 2 — Retry를 CircuitBreaker 밖에 두면 서킷이 원본 실패율을 정확히 반영하고,
     * 열린 뒤에는 재시도가 즉시 실패해 시간을 낭비하지 않는다.</b>
     *
     * <p>같은 "실패, 실패, 성공" 패턴에서 {@code Retry(CircuitBreaker(call))}로 구성하면,
     * 재시도의 매 시도가 개별적으로 CircuitBreaker를 통과한다 — CircuitBreaker의 슬라이딩
     * 윈도우는 원본 호출의 실제 실패율(2/3 ≈ 66.7%)을 그대로 본다. 임계값(50%)을 넘기면
     * 서킷이 OPEN되고, 그 이후의 재시도 시도는 원본 호출까지 가지 않고
     * {@link CallNotPermittedException}으로 즉시 실패한다 — 이미 죽은 걸 아는 대상에게
     * 커넥션/읽기 타임아웃을 매번 다시 기다리지 않는다.
     */
    @Test
    void Retry가_CircuitBreaker_밖에_있으면_서킷이_실제_실패율을_보고_열린_뒤엔_즉시_실패한다() {
        AtomicInteger rawCallCount = new AtomicInteger();
        Supplier<String> flakyRawCall = () -> {
            int n = rawCallCount.incrementAndGet();
            if (n % 3 != 0) {
                throw new RuntimeException("transient failure #" + n);
            }
            return "OK";
        };

        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "mockPg-exp2",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .build());
        Retry retry = Retry.of(
                "mockPg-exp2", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build());

        // Retry(바깥) → CircuitBreaker(안쪽) → 원본 호출. 로드맵 3.1이 권장하는 구성이다.
        Supplier<String> decorated =
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, flakyRawCall));

        // 첫 논리 호출: 원본 3번 시도(실패,실패,성공) 전부 CircuitBreaker를 거친다 —
        // 슬라이딩 윈도우(크기 4)에 실패,실패,성공이 쌓인다(아직 4번째 호출 전이라 OPEN 전).
        assertThat(decorated.get()).isEqualTo("OK");
        assertThat(rawCallCount.get()).isEqualTo(3);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);

        // 두 번째 논리 호출의 첫 시도(4번째 원본 호출, 실패)가 슬라이딩 윈도우를 채우는 순간
        // 실패율이 임계값을 넘어 서킷이 OPEN된다 — 그 뒤 남은 재시도는 원본 호출까지
        // 가지 않고 CallNotPermittedException으로 즉시 끝난다.
        assertThatThrownBy(decorated::get).isInstanceOf(CallNotPermittedException.class);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 서킷이 열리기 전까지 원본 호출은 딱 1번(4번째, 실패) 더 일어났을 뿐이다 — 나머지
        // 재시도 2번은 CallNotPermittedException으로 즉시 끝나 원본 호출을 하지 않았다.
        assertThat(rawCallCount.get()).isEqualTo(4);
    }

    /**
     * <b>실험 3 — TimeLimiter는 Retry 안쪽, 개별 시도에 밀착해야 한다.</b>
     *
     * <p>TimeLimiter를 Retry 안쪽(각 시도마다 독립 적용)에 두면, 느린 시도 하나가 시간
     * 예산을 다 쓰기 전에 잘려나가고 곧바로 다음 재시도로 넘어간다. 원본 호출이 300ms
     * 걸리고 TimeLimiter 제한이 50ms라면, 3번 재시도해도 총 소요 시간은
     * "300ms × 3"(TimeLimiter가 없거나 Retry 밖에 있었을 때의 상한)이 아니라 대략
     * "50ms × 3 + 재시도 대기시간" 수준에 그쳐야 한다 — 이게 이 테스트가 증명하는 것이다.
     */
    @Test
    void TimeLimiter가_Retry_안쪽에_있으면_시도마다_독립적으로_잘려_전체_대기시간이_누적되지_않는다() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            AtomicInteger attemptCount = new AtomicInteger();
            Supplier<Future<String>> slowFutureSupplier = () -> executor.submit(() -> {
                attemptCount.incrementAndGet();
                Thread.sleep(300);
                return "OK";
            });

            TimeLimiter timeLimiter =
                    TimeLimiter.of("mockPg-exp3", TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(50)).build());
            Retry retry = Retry.of(
                    "mockPg-exp3",
                    RetryConfig.custom()
                            .maxAttempts(3)
                            .waitDuration(Duration.ofMillis(10))
                            .retryExceptions(TimeoutException.class)
                            .build());

            // Retry(바깥) → TimeLimiter(안쪽) → 원본 호출.
            Callable<String> decorated =
                    Retry.decorateCallable(retry, TimeLimiter.decorateFutureSupplier(timeLimiter, slowFutureSupplier));

            long start = System.nanoTime();
            assertThatThrownBy(decorated::call).isInstanceOf(TimeoutException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // 시도 3번 전부 원본 호출(300ms 슬립)이 끝나길 기다렸다면 900ms 이상 걸렸을 것이다.
            // TimeLimiter가 시도마다 50ms에서 끊었다면 3 * 50ms + 재시도 대기 2 * 10ms 근처,
            // 넉넉히 잡아도 400ms를 넘지 않는다 — CI 스케줄링 지연을 감안한 보수적인 상한이다.
            assertThat(elapsedMs).isLessThan(400);
            assertThat(attemptCount.get()).isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }
}
