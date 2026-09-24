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

**실험 1 — 지속적 PG 불능이면 지수 백오프로 재시도하다 결국 UNKNOWN으로 포기한다**:
`forceTimeout=true`로 PG를 계속 불능 상태로 만들고 클라이언트 읽기 타임아웃을 100ms로
짧게 준 뒤(`maxAttempts=3`, 초기 대기 200ms, 배율 2) 서킷은 절대 열리지 않을 만큼 임계치를
넉넉히 잡아 순수하게 Retry만 관찰했다. 시도 3회(각 ~100ms) + 백오프 2회(200ms, 400ms)로
총 소요시간이 700ms 이상 걸린다는 사실로 지수 백오프가 실제로 두 번 적용됐음을 증명했다
(고정 대기였다면 훨씬 짧거나 훨씬 길게 나왔을 것).

**실험 2 — 재시도 도중 서킷이 열리면 남은 재시도는 즉시 실패로 끝나 백오프를 낭비하지
않는다**: `maxAttempts=5`로 여유를 주고 슬라이딩 윈도우 크기 2로 서킷이 2번 만에 열리게
설정했다. 실제 흐름은 시도1(실패, 1/2) → 백오프 150ms → 시도2(실패, 2/2, 서킷 OPEN) →
백오프 300ms → 시도3은 `CallNotPermittedException`으로 즉시 끝난다(원본 호출 없음,
`retry-exceptions`에 없어 재시도도 안 함) — 합쳐서 1.5초 미만. 만약
`CallNotPermittedException`도 재시도 대상이었다면 남은 두 번의 백오프(600ms, 1200ms)가
더 붙어 2.4초를 넘겼을 것이다.

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
