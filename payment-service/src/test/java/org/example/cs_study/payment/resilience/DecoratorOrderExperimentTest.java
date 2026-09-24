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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        // CallNotPermittedException은 재시도 대상에서 뺀다 — 서킷이 이미 열렸다는 뜻이라
        // 재시도해봐야 또 즉시 거부될 뿐이다. 이걸 빼지 않으면 남은 재시도 예산(대기시간
        // 포함)을 "이미 결론 난" 거부에 낭비하게 된다(CodeRabbit 리뷰, PR #85).
        Retry retry = Retry.of(
                "mockPg-exp2",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(1))
                        .ignoreExceptions(CallNotPermittedException.class)
                        .build());

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
        // rawCallCount만으로는 부족하다 — ignoreExceptions가 빠졌어도 재시도 자체는 계속
        // 일어나면서 매번 CallNotPermittedException으로 원본 호출 없이 끝났을 수 있고, 그러면
        // rawCallCount는 똑같이 4로 남는다(CodeRabbit 리뷰, PR #85). Retry의 총 시도 횟수
        // (getNumberOfTotalCalls, CB에 거부당한 시도도 "시도"로 센다)까지 확인해야 "재시도
        // 자체가 멈췄다"는 걸 증명한다 — 첫 논리 호출 3번 시도(실패,실패,성공) + 두 번째
        // 논리 호출 2번 시도(1번째: 원본 호출 실패로 서킷 OPEN, 2번째: CallNotPermittedException
        // 즉시 거부 — ignoreExceptions 덕분에 여기서 멈추고 3번째 시도로 넘어가지 않는다)로
        // 총 5번이어야 한다. ignoreExceptions가 빠졌다면 3번째 시도까지 거부당해 6이 됐을 것이다.
        assertThat(retry.getMetrics().getNumberOfTotalCalls()).isEqualTo(5);
    }

    /**
     * <b>실험 3 — TimeLimiter는 Retry 안쪽, 개별 시도에 밀착해야 한다.</b>
     *
     * <p>TimeLimiter를 Retry 안쪽(각 시도마다 독립 적용)에 두면, 느린 시도 하나가 시간
     * 예산을 다 쓰기 전에 잘려나가고 곧바로 다음 재시도로 넘어간다 — 원본 호출이 아무리
     * 오래 걸려도 매 시도는 똑같은 타임아웃 예산을 받는다("50ms × 3"이지 시도가 쌓일수록
     * 남은 예산이 줄어드는 누적 방식이 아니다).
     *
     * <p>실제 300ms 슬립과 실제 경과 시간 측정(wall-clock) 대신, {@link Future#get(long,
     * TimeUnit)}만 흉내 내는 가짜 {@link Future}를 쓴다 — 절대 완료되지 않고 호출될 때마다
     * 즉시 {@link TimeoutException}을 던지면서 요청받은 타임아웃 값을 기록한다. CI 스케줄링
     * 지연에 따라 실제 걸린 시간이 흔들려 간헐적으로 실패하는 일(CodeRabbit 리뷰, PR #85)
     * 자체가 구조적으로 불가능하다 — 시간이 전혀 안 걸리기 때문이다.
     */
    @Test
    void TimeLimiter가_Retry_안쪽에_있으면_시도마다_독립적인_시간_예산을_받는다() throws Exception {
        List<Long> requestedTimeoutsMs = new CopyOnWriteArrayList<>();
        AtomicInteger supplierInvocations = new AtomicInteger();

        // 절대 완료되지 않는 원본 호출을 흉내 낸다 — get(timeout, unit)이 호출될 때마다 그
        // 타임아웃 값을 기록하고 즉시 TimeoutException을 던진다. 실제로 기다리지 않으므로
        // 테스트가 순식간에 끝난다.
        Supplier<Future<String>> neverCompletingFutureSupplier = () -> {
            supplierInvocations.incrementAndGet();
            return new Future<String>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    return false;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return false;
                }

                @Override
                public String get() {
                    throw new UnsupportedOperationException("이 테스트는 timed get()만 사용한다");
                }

                @Override
                public String get(long timeout, TimeUnit unit) throws TimeoutException {
                    requestedTimeoutsMs.add(unit.toMillis(timeout));
                    throw new TimeoutException("simulated: 절대 완료되지 않음");
                }
            };
        };

        TimeLimiter timeLimiter =
                TimeLimiter.of("mockPg-exp3", TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(50)).build());
        Retry retry = Retry.of(
                "mockPg-exp3",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(1))
                        .retryExceptions(TimeoutException.class)
                        .build());

        // Retry(바깥) → TimeLimiter(안쪽) → 원본 호출.
        Callable<String> decorated = Retry.decorateCallable(
                retry, TimeLimiter.decorateFutureSupplier(timeLimiter, neverCompletingFutureSupplier));

        assertThatThrownBy(decorated::call).isInstanceOf(TimeoutException.class);

        // 새 Future를 3번 요청했다 — 즉 Retry가 3번 독립적으로 새 시도를 시작했다.
        assertThat(supplierInvocations.get()).isEqualTo(3);
        // 매 시도가 요청한 타임아웃이 항상 50ms로 동일하다. 만약 TimeLimiter가 Retry 전체를
        // 덮는 "누적" 예산이었다면(바깥에 있었다면) 두 번째, 세 번째 시도의 남은 예산은
        // 50ms보다 작아졌어야 한다 — 매번 정확히 50ms라는 사실 자체가 각 시도가 독립적인
        // 새 예산을 받는다는 증거다.
        assertThat(requestedTimeoutsMs).containsExactly(50L, 50L, 50L);
    }
}
