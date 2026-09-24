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

**실험 3 — TimeLimiter는 Retry 안쪽, 개별 시도에 밀착해야 한다**: 원본 호출이 300ms
걸리고 TimeLimiter 제한이 50ms인 상황에서 `Retry(TimeLimiter(call))`로 구성하면, 느린
시도 하나가 시간 예산을 다 쓰기 전에 50ms에서 잘려나가고 곧바로 다음 재시도로 넘어간다.
3번 재시도해도 총 소요 시간은 "300ms × 3 = 900ms 이상"이 아니라 "50ms × 3 + 재시도
대기시간" 수준(테스트에서는 400ms 미만으로 확인)에 그친다. TimeLimiter가 Retry 밖에
있었다면 제한 시간이 재시도 전체를 덮어야 해서, Retry의 `maxAttempts`/`waitDuration`과
TimeLimiter의 `timeoutDuration`이라는 독립적으로 튜닝하고 싶은 두 설정이 서로 얽혀버린다.

**아직 손대지 않은 것**: 이 PR은 순서를 결정하고 순수 단위 테스트로 증명하는 것까지만
다룬다. `PaymentService.doRequestPayment`/`MockPgClient`에 실제로 이 순서를 배선하는 것,
각 데코레이터의 파라미터를 실제 운영값으로 튜닝하는 것, Fallback 설계는 3.2~3.6에서
하나씩 이어간다 — 2.11(테이블+상태 전이)이 2.12(실제 배선)와 태스크를 분리했던 것과
같은 패턴이다.
