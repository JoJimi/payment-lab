# 07. 3단계 — 장애 방어 (Resilience4j)

3단계 3-A(Resilience4j 적용, 이슈 #85~)에서 겪은 문제와 설계 결정을 기록한다.

### 1. 데코레이터 적용 순서 결정 (3.1) — Retry(바깥) → CircuitBreaker → TimeLimiter(안쪽)

**배경**: `payment-service`가 Mock PG를 호출하는 지점(`MockPgClient.requestPayment`, 1.5)에
Retry/CircuitBreaker/TimeLimiter 세 데코레이터를 겹쳐 적용해야 한다. `resilience4j-spring-boot4`
의존성과 `application.yml`의 인스턴스 설정(`mockPg`)은 0단계 시점에 이미 준비돼 있었지만
(`register-health-indicator: false`도 그때 미리 박아둠 — 서킷 OPEN이 5단계 readiness probe에
파드를 빼는 걸 막기 위해), 실제로 어떤 순서로 겹쳐 감싸야 하는지는 로드맵 3.1이 별도
태스크로 떼어놓을 만큼 비자명한 문제다 — 세 데코레이터를 감싸는 순서에 따라 관측되는
동작이 완전히 달라진다.

**결정**: `Retry(CircuitBreaker(TimeLimiter(원본 호출)))` — Retry가 가장 바깥, TimeLimiter가
원본 호출에 가장 밀착. 순수 단위 테스트(Spring 컨텍스트·Docker·네트워크 불필요,
`DecoratorOrderExperimentTest`)로 그 이유를 직접 증명했다.

**실험 1 — CircuitBreaker를 Retry 밖에 두면 서킷이 실제 장애율을 못 본다**: 원본 호출이
"실패, 실패, 성공" 패턴(3번째 시도에서만 성공 — Mock PG가 간헐적으로만 응답하는 상황을
흉내냄)일 때, `CircuitBreaker(Retry(call))`로 구성하면 CircuitBreaker는 "재시도까지 끝낸
최종 결과"만 본다. 5회의 논리적 호출 동안 원본 호출은 15번 일어나고 그중 10번(66.7%)이
실패했지만, CircuitBreaker의 슬라이딩 윈도우에는 성공만 5번 쌓인다 — 서킷은 절대 열리지
않는다. 다운스트림이 실제로는 매우 불안정한데 이 사실이 CircuitBreaker에게 완전히
가려진다.

**실험 2 — Retry를 CircuitBreaker 밖에 두면 서킷이 실제 실패율을 정확히 보고, 열린 뒤엔
재시도가 즉시 실패한다**: 같은 패턴에서 `Retry(CircuitBreaker(call))`로 구성하면, 재시도의
매 시도가 개별적으로 CircuitBreaker를 통과한다 — 두 번째 논리 호출의 첫 시도(4번째 원본
호출)가 슬라이딩 윈도우(크기 4)를 채우는 순간 실제 실패율(50% 임계값)을 넘어 서킷이
OPEN된다. 그 뒤 남은 재시도 2번은 원본 호출까지 가지 않고 `CallNotPermittedException`으로
즉시 끝난다 — 이미 죽은 걸 아는 대상에게 커넥션/읽기 타임아웃을 매번 다시 기다리지 않는다.
이게 Retry를 가장 바깥에 두는 이유다: 재시도 예산을 진짜 장애 감지와 빠른 실패 양쪽에
다 쓸 수 있다.

**실험 3 — TimeLimiter는 Retry 안쪽, 개별 시도에 밀착해야 한다**: `Retry(TimeLimiter(call))`로
구성하면, 매 시도가 독립적인 시간 예산을 받는다 — 누적되지 않는다. 절대 완료되지 않고
`get(timeout, unit)`이 호출될 때마다 그 타임아웃 값을 기록하며 즉시 `TimeoutException`을
던지는 가짜 `Future`로 검증했다(초기 버전은 실제로 300ms 슬립시키고 실제 경과 시간을
재는 wall-clock 방식이었는데, CI 스케줄링 지연에 따라 간헐적으로 실패할 수 있다는 CodeRabbit
리뷰를 받아 시간 측정 자체를 없앴다). 결과: 새 `Future`를 정확히 3번 요청했고(=3번
독립적으로 재시도했고), 매 시도가 요청한 타임아웃이 항상 50ms로 동일했다 — 누적 예산이었다면
두 번째·세 번째 시도의 남은 예산이 50ms보다 작아졌어야 한다. TimeLimiter가 Retry 밖에
있었다면 제한 시간이 재시도 전체를 덮어야 해서, Retry의 `maxAttempts`/`waitDuration`과
TimeLimiter의 `timeoutDuration`이라는 독립적으로 튜닝하고 싶은 두 설정이 서로 얽혀버린다.

**아직 손대지 않은 것**: 이 PR은 순서를 결정하고 순수 단위 테스트로 증명하는 것까지만
다룬다. `PaymentService.doRequestPayment`/`MockPgClient`에 실제로 이 순서를 배선하는 것,
각 데코레이터의 파라미터를 실제 운영값으로 튜닝하는 것, Fallback 설계는 3.2~3.6에서
하나씩 이어간다 — 2.11(테이블+상태 전이)이 2.12(실제 배선)와 태스크를 분리했던 것과
같은 패턴이다.

### 2. CircuitBreaker 실제 배선 (3.2) — "정상 실패"와 "PG 불능"을 구분해야 서킷이 의미 있다

**배경**: 3.1이 결정한 순서(Retry → CircuitBreaker → TimeLimiter) 중 CircuitBreaker를
`PaymentService`가 실제로 호출하는 경로에 배선한다. `MockPgClient.requestPayment`를
그대로 `CircuitBreaker.decorateSupplier`로 감싸려다 보니, 이 클라이언트가 지금까지
결과를 **전부 정상 반환값**(`MockPgResult`)으로 전달하고 있다는 문제와 마주쳤다 — 카드
거절(`FAILED`)도, PG가 아예 응답을 못 준 상황(5xx/타임아웃, 지금까지는
`MockPgResult.timedOut()`)도 둘 다 예외 없이 정상 리턴이었다.

**문제**: CircuitBreaker는 예외가 나야만 실패로 기록한다. 카드 거절은 PG가 정상적으로
"이 결제는 안 된다"고 답한 것이지 인프라 장애가 아니다 — 카드 거절이 많다고 서킷이
열리면(예: 프로모션 기간에 한도 초과 결제 시도가 몰릴 때) 정상적으로 응답하고 있는
PG를 향한 트래픽을 스스로 차단하는 자해가 된다. 반대로 PG가 응답을 못 주는 상황
(5xx, 커넥션/읽기 타임아웃, 파싱 불가)은 `MockPgResult.timedOut()`이라는 "정상 반환값"
뒤에 숨어 있어서 CircuitBreaker가 절대 감지하지 못한다.

**결정**: `MockPgClient`가 "PG 불능" 상황에서는 `MockPgUnavailableException`을 던지도록
바꿨다 — 카드 거절/승인은 여전히 정상 반환(`MockPgResult.approved`/`failed`), PG 자체가
응답을 못 준 경우만 예외로 승격한다. `PaymentService`와 `MockPgClient` 사이에
`ResilientMockPgGateway`를 새로 끼워 CircuitBreaker를 소유하게 했다 — 이 게이트웨이가
`MockPgUnavailableException`(원본 호출 실패)과 `CallNotPermittedException`(서킷이 이미
열려 원본 호출조차 안 감)을 둘 다 잡아 `MockPgResult.timedOut()`으로 다시 번역해
돌려준다. `PaymentService` 입장에서는 인터페이스가 바뀌지 않는다 — 여전히
`MockPgResult`만 받는다.

**서킷 OPEN을 왜 FAILED가 아니라 UNKNOWN으로 번역하는가**: 서킷이 열려 원본 호출조차
시도하지 않았다는 것은 "PG가 거절했다"는 증거가 전혀 없다는 뜻이다 — 결제가 실제로는
승인됐을 수도 있는데 FAILED로 단정하면 order-service가 보상(재고 해제, 주문 취소)을
잘못 개시한다. `PaymentService.applyResult`가 이미 지키던 원칙(TIMEOUT은 UNKNOWN이지
FAILED가 아니다, 1단계·부록 A-1)을 CircuitBreaker OPEN 상황까지 그대로 확장한 것이다.

**증명**: `ResilientMockPgGatewayTest`(Spring 컨텍스트 불필요, `MockPgServer`를
인프로세스로 띄우고 CircuitBreaker는 core API로 직접 구성)로 세 가지를 확인했다 — ①
정상 응답은 그대로 전달됨, ② `failureRate=1.0`(매번 정상적으로 카드 거절)으로 5번
반복해도 서킷은 CLOSED를 유지함(예외가 없으니 CircuitBreaker가 볼 게 없다), ③
`forceTimeout=true`로 PG 불능을 반복하면 슬라이딩 윈도우가 채워지는 즉시 서킷이 열리고,
그 뒤로는 원본 호출(5초 읽기 타임아웃)까지 가지 않고 500ms 안에 `TIMEOUT`을 즉시
반환한다.

**여전히 남은 것**: Retry와 TimeLimiter는 아직 감지 않았다 — 3.1이 정한 순서대로
3.3(Retry, 지수 백오프+Jitter)과 3.4(TimeLimiter, 지금은 `MockPgClient`의 고정 5초
읽기 타임아웃뿐)에서 `ResilientMockPgGateway`의 안쪽(TimeLimiter)과 바깥쪽(Retry)에
이어붙인다.

### 3. Retry 배선 — 지수 백오프 + Jitter, 그리고 왜 재시도가 안전한가 (3.3)

**배경**: 3.1이 결정한 순서대로 Retry를 CircuitBreaker 바깥에 씌워야 한다. 그런데 재시도를
덧붙이기 전에 먼저 답해야 할 질문이 있다 — 결제 요청을 재시도해도 정말 안전한가? 재시도
때문에 같은 결제가 두 번 승인되면 안 된다.

**재시도가 안전한 이유**: `ResilientMockPgGateway.requestPayment(idempotencyKey, ...)`는
`idempotencyKey`를 파라미터로 받아 CircuitBreaker/Retry로 감싸는 람다 안에서 그대로
재사용한다 — Retry가 몇 번을 재시도하든 매 시도가 정확히 같은 키로 `MockPgClient`를
호출한다. `MockPgServer`(1.6)는 같은 `idempotencyKey`를 최초 1회만 실제로 처리하고, 이미
처리 중이거나 끝난 키로 다시 들어온 요청은 그 결과가 나올 때까지 기다렸다가 동일한 응답을
재현한다(`idempotencyCache.computeIfAbsent`). 즉 재시도 안전성은 Retry 설정이 아니라
"재시도 전체에서 같은 키를 재사용한다"는 호출부의 구조 자체가 보장한다 — 새 코드를 추가로
짤 필요가 없었고, 대신 이 사실을 테스트로 증명해 회귀를 잡아냈다(아래 실험 3).

**결정**: `RetryConfig`에 `retry-exceptions: [MockPgUnavailableException]`만 지정한다 —
카드 거절 등 정상 비즈니스 실패는 애초에 예외를 던지지 않으니 재시도 대상이 아니고(3.2),
서킷이 이미 열려 원본 호출조차 못 간 `CallNotPermittedException`도 재시도 목록에서 뺀다.
이미 열렸다고 확인된 서킷을 다시 두드려봐야 또 즉시 거부될 뿐인데, 빼지 않으면 남은
재시도 예산(대기시간 포함)을 낭비한다 — 3.1 실험 2와 같은 이유다. 대기시간은 지수
백오프(500ms → 1000ms, `exponential-backoff-multiplier: 2`)에 ±50% 무작위 지터
(`randomized-wait-factor: 0.5`)를 얹었다 — 여러 인스턴스가 동시에 PG 장애를 겪을 때
재시도가 한 타이밍에 몰려 막 회복 중인 PG에 다시 부하를 몰아주는 걸(thundering herd) 막기
위해서다.

**추가 수정 — `retry-exceptions`만으로는 부족했다(CodeRabbit 리뷰, PR #87)**: 위 설계는
"서킷이 이미 열려 있으면"만 다룬다. 그런데 서킷을 실제로 여는 바로 그 실패는 어떨까?
그 실패 자체는 아직 `MockPgUnavailableException`이라 재시도 대상이다 — Retry는 이걸 보고
다음 시도 전에 백오프를 한 번 더 기다리고, 그 다음 시도에서야 비로소 서킷 OPEN을
만나 `CallNotPermittedException`으로 끝난다. 즉 서킷을 여는 실패 직후 백오프 한 번만큼은
항상 낭비된다 — `retry-exceptions`는 예외의 "타입"만 보고 "지금 서킷이 열려 있는지"는
모르기 때문이다. `ResilientMockPgGateway` 생성자에서 `retryOnException` 프레디케이트를
직접 덮어써 `circuitBreaker.getState() != OPEN`까지 함께 확인하도록 고쳤다(아래 실험 2가
이 수정 전/후 차이를 보여준다). 여기서 함정 하나 — `RetryConfig.from(base)`로 얻은
빌더는 `base`의 `retryExceptions` 클래스 목록도 그대로 들고 온다. resilience4j는 그
목록에서 만든 프레디케이트와 `retryOnException`으로 준 프레디케이트를 AND가 아니라
**OR**로 합친다(`PredicateCreator`) — 그래서 `retryExceptions()`를 인자 없이 호출해
먼저 명시적으로 비우지 않으면, "타입이 맞다"는 조건이 OR로 살아남아 서킷 상태 확인이
통째로 무력화된다. 바이트코드까지 뒤져서 이 함정을 확인하고 나서야 고쳤다.

**실험 1 — 지속적 PG 불능이면 지수 백오프로 재시도하다 결국 UNKNOWN으로 포기한다**:
`forceTimeout=true`로 PG를 계속 불능 상태로 만들고 클라이언트 읽기 타임아웃을 100ms로
짧게 준 뒤(`maxAttempts=3`, 초기 대기 200ms, 배율 2) 서킷은 절대 열리지 않을 만큼 임계치를
넉넉히 잡아 순수하게 Retry만 관찰했다. 시도 3회(각 ~100ms) + 백오프 2회(200ms, 400ms)로
총 소요시간이 700ms 이상 걸린다는 사실로 지수 백오프가 실제로 두 번 적용됐음을 증명했다
(고정 대기였다면 훨씬 짧거나 훨씬 길게 나왔을 것).

**실험 2 — 서킷을 연 실패 뒤에는 백오프 없이 그 자리에서 재시도를 멈춘다**: `maxAttempts=5`로
여유를 주고 슬라이딩 윈도우 크기 2로 서킷이 2번 만에 열리게 설정했다. 수정 전 흐름은
시도1(실패, 1/2) → 백오프 150ms → 시도2(실패, 2/2, 이 실패가 서킷을 OPEN으로 만든다) →
**백오프 300ms(낭비)** → 시도3은 원본 호출 없이 `CallNotPermittedException`으로 끝난다 —
합쳐서 650ms 안팎. 수정 후에는 시도2가 서킷을 여는 바로 그 순간 프레디케이트가
`state != OPEN`을 확인해 즉시 멈춘다 — 시도3도, 그 앞의 백오프 300ms도 없다. 합쳐서
350ms 안팎(테스트에서는 200~600ms 범위로 확인, CI 스케줄링 변동을 감안한 보수적인 폭)
— `maxAttempts`를 5로 넉넉히 줬어도 실제로는 딱 2번만 시도된다는 뜻이다.

**실험 3 — 같은 idempotencyKey로 재시도해도 PG는 한 번만 처리하고 같은 결과를 재현한다**:
PG 응답 지연을 300ms로, 클라이언트 읽기 타임아웃을 100ms로 설정했다. 첫 시도는 반드시
클라이언트 타임아웃으로 실패하지만 서버 쪽 처리는 취소되지 않고 계속 진행된다 — 백오프
(250ms) 뒤의 두 번째 시도가 도착할 시점(300ms 경과 후)엔 이미 완료된 결과를 즉시
재현받아 APPROVED로 끝난다. 만약 재시도마다 `idempotencyKey`를 새로 생성하는 버그가
있었다면 매 시도가 처음부터 300ms 지연을 다시 겪어 3번 다 타임아웃으로 소진되고
TIMEOUT이 됐을 것이다 — 여기서 APPROVED가 나온다는 사실 자체가 재시도 전체에서 같은
키를 재사용한다는 증거이자, 이 성질이 깨지면 실패하는 회귀 테스트다.

**아직 손대지 않은 것**: TimeLimiter는 여전히 감지 않았다 — `MockPgClient`의 고정 5초
읽기 타임아웃을 실제 Resilience4j `TimeLimiter`로 교체하는 것은 3.4에서 이어간다.

### 4. TimeLimiter 배선 — 고정 5초 타임아웃을 대체하고, blocking 클라이언트의 한계를 받아들인다 (3.4)

**배경**: 3.1이 정한 순서(Retry 바깥 → CircuitBreaker → TimeLimiter 안쪽)의 마지막 조각이다.
지금까지 "시간 제한"은 `MockPgClient`가 `SimpleClientHttpRequestFactory`에 하드코딩한
5초 읽기 타임아웃뿐이었다 — Resilience4j `TimeLimiter`가 관여하지 않았고, 그래서 3.1
실험 3이 증명한 "매 재시도가 독립적인 시간 예산을 받는다"는 성질도 실제 운영 경로에는
아직 적용돼 있지 않았다.

**문제 — 동기(blocking) 클라이언트를 `TimeLimiter`에 어떻게 물리는가**: `TimeLimiter`의
API(`decorateFutureSupplier`)는 `Future`를 요구하는데, `MockPgClient.requestPayment`는
동기 호출이라 `Future`가 없다. 별도 스레드에 위임해야 `Future`를 만들 수 있다 —
`ResilientMockPgGateway`가 소유한 `ExecutorService`(`executor.submit(() ->
mockPgClient.requestPayment(...))`)가 그 역할을 한다.

**결정**: `TimeLimiter.decorateFutureSupplier(timeLimiter, () -> executor.submit(...))`로
얻은 `Callable`을 `CircuitBreaker.decorateCallable`로, 다시 `Retry.decorateCallable`로
감싼다 — 3.1이 정한 순서 그대로다. `TimeLimiter`가 던지는 `TimeoutException`은
`MockPgUnavailableException`과 동일하게 취급한다 — "시간 안에 응답 못 받음"도 "승인/거절을
확정할 수 없다"는 뜻이라 재시도 대상이고(서킷 상태 확인 포함, 3.3), 최종적으로도
`MockPgResult.timedOut()`(UNKNOWN)으로 번역된다. `MockPgClient`의 읽기 타임아웃은 더 이상
독자적인 5초 고정값이 아니라 `resilience4j.timelimiter.instances.mockPg.timeout-duration`
(현재 3초)과 같은 값을 `@Value`로 공유해서 쓴다.

**한계 — `cancelRunningFuture`가 실제 소켓을 끊지는 못한다**: `TimeLimiter`는 시간이
지나면 기본적으로 `future.cancel(true)`를 호출해 실행 중인 `Future`에 인터럽트를 보낸다.
그런데 `MockPgClient`가 쓰는 `SimpleClientHttpRequestFactory`(내부적으로
`HttpURLConnection`)는 blocking I/O라 `Thread.interrupt()`에 반응하지 않는다 — 인터럽트
플래그만 세워질 뿐, 소켓 읽기는 계속 블로킹된 채로 남는다. 즉 `TimeLimiter`가 "논리적으로"
포기하고 호출자에게 `TimeoutException`을 던진 뒤에도, 그 요청을 처리하던 백그라운드
스레드는 `MockPgClient`의 자체 읽기 타임아웃이 실제로 터질 때까지 계속 점유된 채
남아있을 수 있다. 이걸 근본적으로 막으려면(전용 스레드풀 크기 제한 + 거부 정책) 3.5
(Bulkhead)가 필요하다 — 3.4는 그 차이를 최소화하는 선에서 그친다: `MockPgClient`의 읽기
타임아웃을 `TimeLimiter`의 `timeout-duration`과 같은 값으로 맞춰, 논리적 타임아웃과
"실제로 스레드가 붙들려 있는" 상한이 크게 벌어지지 않게 했다. 스레드가 격리되지 않은 채
남는 문제 자체는 여전히 해결되지 않았다는 걸 분명히 해둔다.

**증명 — PG가 응답은 하지만 느리면 TimeLimiter가 클라이언트 타임아웃보다 먼저 끊는다**:
`MockPgServer`의 응답 지연을 1000ms로, `TimeLimiterConfig`의 `timeoutDuration`을 200ms로
짧게 준 뒤(클라이언트 읽기 타임아웃은 5초로 넉넉히 둬서 간섭하지 않게 함) 호출했다.
결과는 `TIMEOUT`(UNKNOWN)이고 실제 경과 시간은 1000ms보다 훨씬 짧다(측정값 약 211ms) —
클라이언트의 5초 소켓 타임아웃이 아니라 `TimeLimiter`의 200ms가 먼저 끊었다는 뜻이다.
`ResilientMockPgGatewayTest`의 기존 6개 테스트는 전부 10초짜리 넉넉한 `TimeLimiterConfig`
기본값을 쓰도록 해서, 3.3까지 검증했던 Retry/CircuitBreaker 동작이 새 TimeLimiter 계층
때문에 흔들리지 않았음을 함께 확인했다(7/7 통과).

**아직 손대지 않은 것**: PG 호출 전용 스레드풀의 크기 제한과 거부 정책, 그리고 그
스레드풀이 다른 작업과 자원을 다투지 않도록 격리하는 것은 3.5(Bulkhead)에서 이어간다.

### 5. Bulkhead 배선 — 무제한 스레드풀을 제한된 크기로 격리한다 (3.5)

**배경**: 3.4는 `TimeLimiter`가 요구하는 `Future`를 만들려고 `ResilientMockPgGateway`가
직접 관리하는 `Executors.newCachedThreadPool()`을 썼다. 3.4 문서에 이미 적어뒀듯 이건
"임시 조치"였다 — PG가 느려지면 이 풀은 한도 없이 스레드를 늘려가며 다른 작업의 CPU/메모리를
잠식할 수 있는 상태였다. 3.1이 정한 순서(Retry → CircuitBreaker → TimeLimiter → Bulkhead)의
마지막 조각을 채운다.

**결정**: 수작업 `ExecutorService`를 Resilience4j의 `ThreadPoolBulkhead`로 교체했다 —
`resilience4j.thread-pool-bulkhead.instances.mockPg`(`core-thread-pool-size: 4`,
`max-thread-pool-size: 8`, `queue-capacity: 8`)로 크기를 명시적으로 제한한다. core/max
스레드와 큐가 모두 찬 상태에서 새 요청이 오면 `ThreadPoolBulkhead.submit`이 스레드도 큐도
쓰지 않고 그 자리에서 `BulkheadFullException`을 던진다(동기적으로 — 내부적으로
`RejectedExecutionException`을 잡아 변환한다는 것을 `FixedThreadPoolBulkhead` 바이트코드로
확인했다). 이 예외도 `MockPgUnavailableException`/`TimeoutException`과 동일하게 취급한다 —
재시도 대상(서킷 상태 확인 포함, 3.3)이고 최종적으로 `MockPgResult.timedOut()`(UNKNOWN)으로
번역된다. PG 자체는 멀쩡해도 우리 쪽 처리 능력이 바닥났다는 것 역시 "승인/거절을 확정할 수
없다"는 뜻이기 때문이다.

**함정 — `ThreadPoolBulkhead.decorateCallable`은 쓸 수 없었다**: 처음엔 다른 데코레이터들과
통일된 스타일로 `ThreadPoolBulkhead.decorateCallable(bulkhead, callable)`을 쓰려 했다.
그런데 이 정적 메서드는 `Supplier<CompletionStage<T>>`를 반환한다 — `TimeLimiter`가
요구하는 `Supplier<Future<T>>`와 호환되지 않는다(`CompletionStage`는 `Future`를 확장하지
않는다). 대신 인터페이스에 있는 인스턴스 메서드 `threadPoolBulkhead.submit(Callable<T>)`를
직접 쓰고 그 반환값(선언 타입은 `CompletionStage<T>`이지만 실제로는 `CompletableFuture`)에
`.toCompletableFuture()`를 호출해 `Future<T>` 계약을 만족시켰다. `javap`으로
`ThreadPoolBulkhead` 인터페이스와 그 구현체(`FixedThreadPoolBulkhead`)의 바이트코드를 직접
비교하고 나서야 이 차이를 확인했다 — 구현체에는 `CompletableFuture`를 직접 반환하는
오버로드도 있지만, 인터페이스 타입으로 참조하는 한 그 오버로드는 보이지 않는다.

**증명 — 동시 PG 호출이 스레드풀 용량을 넘으면 초과분은 BulkheadFullException으로 즉시
거부된다**: `coreThreadPoolSize=1`, `maxThreadPoolSize=1`, `queueCapacity=1`로 좁혀
동시에 받아줄 수 있는 요청을 딱 2건(실행 중 1 + 대기 1)으로 제한했다. PG 응답을 300ms
지연시키고(정상 처리, 장애 아님) `CountDownLatch`로 3개의 요청을 동시에 쏜 뒤 결과를
모았다 — 2건은 APPROVED(하나는 즉시 실행, 하나는 큐에서 잠깐 기다렸다가 실행), 1건은
TIMEOUT(BulkheadFullException으로 즉시 거부)이었다. 5회 반복 실행으로 타이밍에 따른
플레이키니스가 없음을 확인했다.

**한계 — 여전히 남은 것**: Bulkhead는 "동시에 몇 건까지 받아줄지"의 상한일 뿐, 3.4가 남긴
근본적인 한계(blocking HTTP 클라이언트가 인터럽트에 응답하지 않아 타임아웃 이후에도 소켓을
붙들고 있을 수 있다는 것) 자체를 없애지는 못한다. 다만 이제 그 "붙들려 있는" 스레드의
개수에 확실한 상한이 생겼다는 점이 3.4와의 차이다 — 전에는 무제한으로 늘어날 수 있었다.
Fallback 설계(서킷이 OPEN이거나 Bulkhead가 가득 찼을 때 무엇을 돌려줄지)는 3.6에서
이어간다.

**추가 수정 1 — Bulkhead 거부가 CircuitBreaker 실패로 잘못 잡혔다(CodeRabbit 리뷰, PR
#89)**: `CircuitBreaker.decorateCallable`이 Bulkhead의 `submit`을 감싸는 구조라, 풀과
큐가 가득 차 `BulkheadFullException`이 나면 그 예외도 그대로 CircuitBreaker에 기록됐다.
부하가 몰려 거부가 늘면(PG 자체는 멀쩡한데도) 서킷이 열려버리고, 부하가 풀린 뒤의 정상
요청까지 `CallNotPermittedException`으로 막혀 원본 호출 없이 UNKNOWN이 되는 문제였다 —
카드 거절이 CircuitBreaker에 안 잡히는 3.2의 원칙과 같은 이유로, `application.yml`의
`resilience4j.circuitbreaker.instances.mockPg.ignore-exceptions`에
`BulkheadFullException`을 추가해 실패 집계에서 뺐다. `coreThreadPoolSize=1`,
`maxThreadPoolSize=1`, `queueCapacity=0`인 서킷에 동시 요청 5건을 쏴서(1건만 실행,
4건은 즉시 거부) 그 직후의 요청이 여전히 원본 호출까지 도달해 정상 승인되는지로
증명했다 — `ignoreExceptions` 없이 같은 시나리오를 돌리면 슬라이딩 윈도우가 금방
임계값을 넘어 이 마지막 요청도 즉시 거부됐을 것이다.

**추가 수정 2 — 큐에서 대기 중인 작업은 `cancel(true)`로 막을 수 없었다(CodeRabbit 리뷰,
PR #89)**: 3.4에서 인터럽트 시 `future.cancel(true)`를 호출하도록 고쳤을 때는 이게 아직
실행을 시작하지 않은 작업도 막아줄 거라 생각했다. 3.5로 넘어오면서 이 가정이 깨졌다 —
`ThreadPoolBulkhead.submit()`은 실제 제출은 내부적으로 만든 별도의 future로 하고, 우리에게
돌려주는 건 그 결과를 나중에 전달만 받는 새 `CompletableFuture`다. `CompletableFuture`는
애초에 인터럽트로 처리를 제어하지 않는다는 JDK 계약도 있어(Javadoc), 우리가 들고 있는
future에 `cancel(true)`를 불러도 큐에 그대로 남아있는 실제 작업은 전혀 영향을 받지 않고
나중에 실행돼 PG를 호출할 수 있었다 — 이미 UNKNOWN으로 답을 준 요청인데도. 고친 방법은
제출하는 작업 자체에 "이미 포기했다" 플래그(`AtomicBoolean`)를 심는 것이다 — 아직 큐에서
대기 중인 작업이라면 PG를 부르기 직전에 그 플래그를 보고 스스로 멈춘다. 실행을 시작해
블로킹 중인 작업까지는 여전히 못 막는다(3.4의 한계 그대로). 증명은 블랙박스로 했다 —
용량 2(실행 1 + 대기 1)인 게이트웨이에 "차단용" 요청으로 실행 슬롯을 300ms 채운 뒤,
"대상" 요청을 큐에 넣자마자 그 호출자를 인터럽트했다. 대기 시간을 충분히 준 다음
`MockPgClient`로 같은 키를 직접 호출해봤을 때, 그 호출이 300ms 지연을 고스란히 겪고서야
승인됐다는 사실 자체가 큐에 있던 작업이 실제로는 한 번도 PG를 부르지 않았다는 증거다 —
만약 불렀다면 MockPgServer의 멱등성 캐시(1.6)에 이미 결과가 있어 직접 호출이 즉시
끝났을 것이다.

**추가 수정 3 — 취소 플래그를 요청 전체에서 공유하면 안 됐다(CodeRabbit 리뷰, PR #89
3차)**: 추가 수정 2의 `AtomicBoolean` 플래그를 `requestPayment()` 메서드 맨 위에서 한 번만
만들어 모든 재시도 시도가 공유하게 짰더니 두 가지 문제가 있었다. 첫째, 이 플래그는
`catch (InterruptedException e)` 경로에서만 세워졌다 — `TimeLimiter` 자신의 타임아웃
(`TimeoutException` 경로, `TimeLimiterImpl`이 내부적으로 `future.cancel(true)`를 부르는
경우)으로 시도가 취소될 때는 전혀 세워지지 않아, 그 시도가 여전히 큐에 남아있었다면
추가 수정 2가 막으려던 문제(취소된 시도가 그래도 PG를 부름)가 TimeLimiter 타임아웃
경로에서는 재발했다. 둘째, 이 문제를 "그럼 TimeLimiter 타임아웃 때도 플래그를
세우면 되지 않나" 식으로 단순하게 고치면 새 문제가 생긴다 — 플래그가 요청 전체에서
하나뿐이라, 시도 1이 타임아웃으로 취소되며 플래그를 세우면 아직 시작도 안 한 시도
2(다음 재시도)까지 같은 플래그를 보고 "이미 취소됐다"며 PG를 아예 부르지 않고
넘어가버린다 — 재시도 자체가 무력화된다. 고친 방법은 플래그를 `TimeLimiter.
decorateFutureSupplier` 람다 안, 즉 시도마다 새로 만드는 것이다(`AtomicBoolean
attemptCancelled = new AtomicBoolean(false)`) — 그리고 future를 만든 직후
`future.whenComplete((result, error) -> { if (future.isCancelled()) attemptCancelled.set(true); }
)`을 등록해, 그 시도의 future가 취소되는 모든 경로(호출자 인터럽트로 인한 명시적
`future.cancel(true)`든, `TimeLimiterImpl`이 타임아웃으로 내부에서 부르는
`future.cancel(true)`든)에서 그 시도 자신의 플래그만 세우게 했다. 자바 클로저 의미상
람다가 호출될 때마다 새 `AtomicBoolean` 인스턴스가 만들어지므로, 한 시도의 취소가 다른
시도의 플래그를 건드릴 수 없다는 점이 이 수정의 정확성을 보장한다 — 별도의 재현
테스트 없이도 이 보장 자체가 언어 수준의 성질이라 자명하다고 판단했다.

### 6. Fallback 설계 — 즉시 실패(UNKNOWN)로 확정하고, 회수는 Saga 타임아웃에 맡긴다 (3.6)

**배경**: 3.1~3.5가 배선한 네 데코레이터(Retry/CircuitBreaker/TimeLimiter/Bulkhead)는
모두 결국 같은 질문에 부딪힌다 — 서킷이 OPEN이거나, PG가 시간 안에 응답하지 않거나,
Bulkhead가 가득 차서 원본 호출 자체를 시도하지 못했을 때 `PaymentService`에 무엇을
돌려줄 것인가? 로드맵 3.6이 제시하는 선택지는 두 가지다: **즉시 실패**(그 자리에서
확정하지 못한 상태로 응답을 끝냄) vs **큐잉 후 지연 처리**(요청을 어딘가에 쌓아뒀다가
장애가 풀리면 다시 시도함).

**결정 — 이미 3.2에서 내려져 있었다**: `ResilientMockPgGateway.requestPayment`는 네
데코레이터가 던지는 모든 기술적 실패(`MockPgUnavailableException`, `CallNotPermittedException`,
`TimeoutException`, `BulkheadFullException`)를 한 catch 블록에서 잡아 즉시
`MockPgResult.timedOut()`으로 번역해 돌려준다(큐잉도, 별도 재시도 스레드도 없다) —
"즉시 실패"다. `PaymentService.applyResult`는 이 `TIMEOUT`을 받으면 `payment.markUnknown()`만
하고 `payment.completed`도 `payment.failed`도 Outbox에 적재하지 않는다(위 Javadoc,
`applyResult` 참고). 즉 payment-service는 "모르겠다"는 사실 자체를 이벤트로 확정 짓지
않고 조용히 멈춘다 — order-service의 Saga는 PAYMENT 단계에 `STARTED`로 그대로 남는다.

**왜 큐잉이 아니라 즉시 실패인가**: 이 시스템의 "지연 처리" 메커니즘은 이미 다른 곳에
존재한다 — `SagaTimeoutScheduler`(2.15)가 30초마다(`app.saga.timeout.scheduler.fixed-delay-ms`)
`timeout_at`을 넘긴 채 `STARTED`로 멈춘 Saga를 찾아 `SagaTimeoutService.reclaim`으로
회수한다. payment-service 안에 큐잉이나 재시도 워커를 따로 만들면 이미 있는 이 회수
경로와 사실상 같은 일을 하는 두 번째 메커니즘이 생긴다 — 서로 다른 두 컴포넌트가 "이
결제, 아직 살아있나?"를 각자 판단하게 되고, 둘의 타이밍이 어긋나면(예: payment-service
내부 큐가 재시도하는 도중 Saga가 먼저 타임아웃으로 보상을 시작) 이중 처리나 레이스가
생길 여지가 커진다. "확정하지 못하면 즉시 UNKNOWN으로 끝내고 회수는 상위(Saga)에
맡긴다"는 원칙 하나로 통일하는 편이 안전하다 — 1단계 부록 A-1이 정한 "TIMEOUT은 FAILED가
아니라 UNKNOWN"이라는 원칙의 3단계판 확장이다(3.2 문서 참고).

**Saga 타임아웃 로직과의 연계 — 예산이 실제로 맞는지 확인한다**: "즉시 실패 후 상위가
회수한다"는 설계가 성립하려면 Resilience4j가 재시도하며 실제로 소비하는 최악의 시간이
Saga의 타임아웃 예산보다 충분히 작아야 한다 — 그렇지 않으면 Saga가 아직 재시도 중인
결제를 성급하게 타임아웃으로 회수해 보상을 시작해버리는 레이스가 생긴다.
`application.yml`(payment-service)의 현재 값으로 최악의 경우를 계산하면:
- 시도 1회당 상한은 `TimeLimiter`의 `timeout-duration`(3s)이다 — 3.5에서 확인했듯 이
  상한은 Bulkhead 큐 대기 시간까지 포함해서 잰다(`TimeLimiter`가 `futureSupplier.get()`
  으로 제출한 직후부터 시계가 돈다).
- `Retry`는 `max-attempts: 3`, 초기 대기 500ms에 배율 2인 지수 백오프(500ms → 1000ms),
  여기에 ±50% 지터가 얹힌다 — 두 백오프 구간의 최악값 합은 (500+1000)×1.5 = 2250ms.
- 최악의 총 소요시간 ≈ 3 × 3s(TimeLimiter 상한) + 2.25s(백오프) = **약 11.25초**.

`app.saga.timeout-minutes`(order-service, 기본값 10분 = 600초)는 이 최악값의 50배가
넘는다 — Resilience4j가 정상적으로 재시도를 다 소진하고 최종적으로 UNKNOWN을 확정하는
동안 Saga 타임아웃 스케줄러가 먼저 끼어들 여지는 사실상 없다. 두 예산 사이에 이 정도
여유를 둔 것은 우연이 아니다 — Saga 타임아웃은 애초에 "실패 이벤트조차 안 오는" 훨씬
드문 장애(서비스 다운, 메시지 유실, 2.15 문서 참고)를 겨냥해 넉넉하게 잡은 값이고,
Resilience4j는 그보다 훨씬 빠른 시간 안에 자체적으로 결론(승인/거절/UNKNOWN)을 낸다 —
서로 다른 시간 스케일에서 서로 다른 장애를 겨냥하도록 설계돼 있다.

**결론**: 3.6은 새로 구현할 코드가 없다 — 3.2가 도입한 예외→UNKNOWN 즉시 번역과 2.12/2.15가
이미 갖춘 "이벤트가 없으면 Saga가 타임아웃으로 회수한다"는 경로가 그 자체로 이 설계
요구사항을 만족한다. 이 절은 그 사실을 명시적으로 검증하고 기록해, "왜 payment-service
안에 별도 재시도 큐를 안 뒀는가"라는 질문에 근거를 남기는 것이 목적이다.

### 7. 장애 시나리오 스크립트와 서킷 상태 전이 관찰 (3.7~3.8)

**배경**: 3.1~3.6이 배선한 방어 로직이 실제로 동작하는지는 코드를 읽는 것만으로는
증명되지 않는다 — Mock PG에 실제로 지연/실패/타임아웃을 주입하면서 서킷이 정말
CLOSED→OPEN→HALF_OPEN→CLOSED로 전이하는지 눈으로 확인해야 한다. 여기서 걸리는 문제가
하나 있다: `PaymentController`의 `POST /api/payments`는 `UnimplementedOrderValidator`
(2.1/2.3, 항상 501)를 거치므로 Mock PG까지 절대 도달하지 못한다. Mock PG를 실제로
때리려면 order-service의 `POST /api/orders`가 Outbox로 발행하는 `payment.requested`를
`PaymentRequestedListener`가 소비해 `requestPaymentFromSaga`로 넘어가는 경로(2.12)를
타야 한다.

**스크립트**: `scripts/fault-scenario-mockpg.sh`(3.7)가 이 경로로 부하를 생성하면서
mock-pg-server의 `POST /pg/_config`로 `delayMs`/`failureRate`/`forceTimeout`을 단계별로
바꾼다. 4개 시나리오(`gradual-latency`/`intermittent-failure`/`complete-down`/
`slow-recovery`) 중 `complete-down`(`forceTimeout=true`, 30초)으로 실제 전이를 확인했다.

**관측 — Grafana state-timeline 패널**: `observability/grafana/dashboards/payment-lab-overview.json`에
`resilience4j_circuitbreaker_state{name="mockPg"} > 0`를 쿼리하는 state-timeline 패널을
추가해 상태를 색깔 구간으로 시각화했다(이 작업 중에 이 대시보드의 다른 5개 패널이
2.1 멀티모듈 분리 이후에도 여전히 `job="payment-lab"`(1단계 모놀리식 시절 라벨)을 쓰고
있어서 전부 No data였던 것도 같이 발견해 서비스별 job으로 고쳤다). `complete-down` 실행
결과, CLOSED → OPEN → HALF_OPEN → CLOSED 전이를 실제로 캡처했다.

**HALF_OPEN에서 한동안 멈춰 있던 이유**: `complete-down` 종료 직후 패널이 HALF_OPEN에서
몇 분간 더 움직이지 않는 게 관찰됐다. `application.yml`의 `mockPg` 설정은
`wait-duration-in-open-state: 10s`, `permitted-number-of-calls-in-half-open-state: 3`이다
— HALF_OPEN은 실제로 Mock PG까지 도달하는 호출이 3번 쌓여야 CLOSED/OPEN 여부를 판단한다.
스크립트의 "정상 구간으로 복귀" 단계가 보낸 주문 중 일부는 아직 OPEN 대기 시간이 안 끝나
즉시 거부됐고, HALF_OPEN 진입 이후 실제로 판단에 반영된 호출이 3번을 못 채운 채 스크립트가
종료돼 트래픽이 끊겼다 — 버그가 아니라 probe 호출이 부족했을 뿐이다. 이후 주문을 몇 개 더
보내 probe 3번을 채우자 정상적으로 CLOSED로 복귀했다. **시사점**: HALF_OPEN 관찰은 장애
시나리오가 끝난 뒤에도 정상 트래픽이 최소 `permitted-number-of-calls-in-half-open-state`
번은 이어져야 완결된다 — `fault-scenario-mockpg.sh`의 "정상 구간으로 복귀" 단계 부하량이
이 값보다 여유 있게 커야 스크립트 실행만으로 풀 사이클이 항상 재현된다는 뜻이기도 하다.
