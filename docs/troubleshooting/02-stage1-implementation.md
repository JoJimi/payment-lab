# 1단계(모놀리식 결제 코어) 구현 트러블슈팅

1단계(1.1~1.22, [PR #36](https://github.com/JoJimi/payment-lab/pull/36))를 구현하며 실전 CI에서
발견한 실패 3건, CodeRabbit 리뷰 1차 라운드의 지적 16건, 그 16건을 고친 커밋이 CI에서 새로
터뜨린 프레임워크 레벨 동시성 버그 1건, 그리고 2차 라운드에서 나온 지적 5건을
원인·해결·영향 범위·재발 방지 순으로 기록합니다. 전부 로컬에서
`./gradlew compileJava compileTestJava`로 컴파일을 검증했고, Docker 없이 돌아가는 테스트
(`MockPgServerTest`, `OrderStatusTest`, `PaymentStatusTest`)는 로컬에서, Testcontainers가
필요한 테스트는 GitHub Actions `build-test` 잡에서 확인했습니다.

환경: Spring Boot 4.1.1 / Java 21 / Gradle 9.7.1 / PostgreSQL 16 / Redis 7 / Redisson 4.7.0

---

## 대원칙

동시성 실험 코드는 "컴파일되고 기동된다"와 "실험이 실제로 의도한 경합을 재현한다"가 별개입니다.
이번에 발견된 문제 대부분은 두 번째 지점 — 테스트 자체가 자신이 증명하려는 조건을 실제로
만들지 못하는 버그, 또는 트랜잭션 경계·어드바이스 순서처럼 Spring이 암묵적으로 정한 기본값이
1단계의 요구사항(짧은 트랜잭션, 외부 I/O를 트랜잭션 밖으로)과 어긋나는 지점에서 나왔습니다.

## 검증 체크리스트

| 대상 | 확인 방법 | 통과 기준 |
|---|---|---|
| 컴파일 | `./gradlew compileJava compileTestJava --no-daemon` | 에러 없음 |
| Docker 불필요 테스트 | `./gradlew test --tests "*MockPgServerTest" --tests "*OrderStatusTest" --tests "*PaymentStatusTest"` | 전부 통과 |
| 동시성 테스트의 스레드풀 크기 | 테스트 코드에서 `Executors.newFixedThreadPool(N)`의 N과 동시 요청 수 비교 | N == 동시 요청 수 (작으면 "동시성"이 풀 크기로 축소됨) |
| 외부 I/O가 트랜잭션 안에 있는가 | `@Transactional` 메서드 바디에서 HTTP/RPC 호출 검색 | 없음 (`.coderabbit.yaml`의 `**/payment/**` 지침) |
| AOP 어드바이스 순서 | 트랜잭션 어드바이저와 경쟁하는 커스텀 `@Aspect`에 `@Order` 명시 여부 | 명시돼 있음 |

---

## 겪은 문제 — CI 실패 (구현 중 2회 라운드)

### 1. `Inventory` 엔티티에 `@PrePersist` 누락 → INSERT 시 `updated_at` NULL 제약 위반

**증상**
`InventoryConcurrencyTest`의 4개 테스트가 전부 `seedProduct()` 단계에서
`DataIntegrityViolationException`으로 실패.

**원인**
`Product`/`Order`/`Payment` 엔티티는 모두 `@PrePersist`로 `createdAt`/`updatedAt`을 채우는데,
`Inventory` 엔티티만 이 콜백이 빠져 있었다. `updated_at nullable=false` 컬럼에 NULL을 그대로
INSERT하려다 DB 제약에 걸렸다.

**해결**
```java
@PrePersist
void onCreate() {
    this.updatedAt = Instant.now();
}
```
다른 세 엔티티와 동일한 패턴으로 추가.

**영향 범위**
`inventory` 패키지를 쓰는 모든 경로. 테스트가 아니었다면 운영에서도 재고 신규 등록이 전부
실패했을 것.

**재발 방지**
새 엔티티를 추가할 때 기존 엔티티(Product/Order/Payment)의 `@PrePersist`/`@PreUpdate` 패턴을
그대로 따라가는지 코드 리뷰에서 확인. (근본적으로는 `AbstractAuditable` 같은 공통 베이스 클래스로
묶을 수 있지만, 엔티티가 4개뿐인 1단계에서는 과한 추상화라 보류.)

---

### 2. 낙관적 락이 300-way 경합에서 재시도(기본 3회)를 소진해 실패

**증상**
`InventoryConcurrencyTest`의 OPTIMISTIC 전략 테스트가 `ObjectOptimisticLockingFailureException`으로
실패. 재고는 남아 있는데 재시도 횟수를 다 써서 주문이 실패 처리됨.

**원인**
이 테스트(1.12)의 목적은 "재고 초과 판매가 없다"(안전성)를 증명하는 것이지 "재시도 3번이면
항상 충분하다"가 아니다. 그런데 재고 1행에 300-way로 몰리는 극단적 경합에서는 기본 재시도
3회로는 부족한 경우가 실제로 생긴다 — 이건 낙관적 락의 알려진 트레이드오프고, 그 자체는
1.14(재시도 1/3/5/10 곡선)가 측정할 몫이다. 즉 프로덕션 버그가 아니라 테스트 범위(안전성 vs
재시도 튜닝)가 섞인 문제였다.

**해결**
`@SpringBootTest(properties = "inventory.optimistic-lock.max-retries=500")`로 이 테스트
클래스에서만 재시도 상한을 크게 올리고, 실패로 셀 예외 범위를 `InsufficientStockException` 외에
`ObjectOptimisticLockingFailureException`/`InventoryLockTimeoutException`까지 넓혔다 — 극단적
경합에서는 이 두 예외도 "이번엔 못 가져갔다"는 같은 의미의 정상적 실패이기 때문이다.

**영향 범위**
`InventoryConcurrencyTest`뿐. 프로덕션 기본값(재시도 3회)은 그대로 유지했다 — 그 값의
타당성은 1.14의 벤치마크가 답할 질문이라 1.12에서 임의로 바꾸지 않는다.

**재발 방지**
"안전성 불변식 테스트"와 "튜닝 파라미터 검증 테스트"를 섞지 않는다. 극단적 동시성 테스트를
새로 작성할 때 재시도/타임아웃 계열 설정값은 안전 마진을 넉넉히 주고, 그 값 자체를 좁히는
실험은 별도 테스트(1.14 같은)로 분리한다.

---

### 3. `PaymentIdempotencyRedisDownTest`의 `Hibernate AssertionFailure` — 재현 안 됨

**증상**
CI 1회 실행에서 `org.hibernate.AssertionFailure`가 라인 89 근처에서 발생. Gradle 콘솔의 축약된
실패 로그에는 예외 타입과 `파일:라인`만 있고 메시지/스택트레이스가 없어 그 자리에서
근본 원인을 특정할 수 없었다.

**원인**
확정하지 못함. 이후 CI 재실행에서 같은 테스트가 깨끗하게 통과했고 재현되지 않았다 — 가능성 있는
후보로 이후 CodeRabbit 리뷰에서 발견한 "동시성 테스트의 스레드풀 크기가 목표 동시 요청 수보다
작아 실제 동시성이 왜곡되는" 버그(아래 10번)를 의심했지만 인과관계를 확인할 방법이 없었다.

**해결**
근본 원인 수정 대신, 재발 시 바로 진단 가능하도록 `build.gradle.kts`에 다음을 추가했다.
```kotlin
testLogging {
    exceptionFormat = TestExceptionFormat.FULL
    showStackTraces = true
    showCauses = true
    events("failed")
}
```

**영향 범위**
1회성/비재현 실패라 실제 영향 범위는 불명. 계측만 강화한 상태.

**재발 방지**
동일 예외가 재발하면 이번엔 전체 스택트레이스가 CI 로그에 그대로 남는다. 그때 근본 원인을
확정하고 이 문서에 추가한다. (원칙: 재현 안 되는 실패를 "일단 재시도해서 통과했으니 넘어간다"로
덮지 않고, 최소한 다음 발생 시 바로 잡을 수 있는 상태로 만들어 둔다.)

---

## 겪은 문제 — CodeRabbit 리뷰 16건 (PR #36)

리뷰 프로필은 `assertive`이고 `request_changes_workflow: false`(머지를 막지 않는 참고용, R.CR4)로
설정돼 있지만, "봇이 낸 지적은 버그 리포트로 취급해 검증 후 고친다"는 운영 원칙에 따라 16건
전부를 실제 결함으로 판단해 수정했다. 예외로 처리하지 않은(옵션 처리하지 않은) 지적은 없었다.

### 4. 상태 전이 위반이 일반 `IllegalStateException`과 섞여 있었음

**증상**
`Order.transitionTo`/`Payment.transitionTo`가 허용되지 않는 상태 전이에서
`IllegalStateException`을 던졌는데, `IdempotencyAspect` 같은 내부 로직도 진짜 버그 상황에서
같은 예외 타입을 던진다. `GlobalExceptionHandler`가 이 둘을 구분할 방법이 없어, 상태 전이
위반(마땅히 409)과 내부 버그(마땅히 500, 메시지 비노출)가 같은 취급을 받을 위험이 있었다.

**원인**
"허용되지 않는 상태 전이"라는 도메인 규칙 위반과, "내부 로직이 가정을 어겼다"는 진짜 버그를
같은 예외 타입으로 표현했기 때문.

**해결**
`InvalidStateTransitionException`(신규, `common` 패키지)을 만들어 `Order`/`Payment`의
`transitionTo`가 이 타입을 던지도록 바꾸고, `GlobalExceptionHandler`에서 일반
`IllegalStateException` 핸들러를 제거한 뒤 `InvalidStateTransitionException`만 409로 매핑했다.
`OrderStatusTest`/`PaymentStatusTest`의 관련 assertion도 새 타입 기준으로 갱신.

**영향 범위**
상태 전이 API 전체(주문 취소, 결제 취소 등)의 에러 응답. 이전에는 내부 버그가 나도 409로
위장돼 클라이언트가 "정상적인 거부"로 오인할 수 있었다.

**재발 방지**
도메인 규칙 위반과 내부 불변식 위반은 항상 별도 예외 타입을 쓴다 — 공용 상위 타입
(`RuntimeException`, `IllegalStateException`)을 그대로 던지지 않는다.

---

### 5. Grafana 대시보드가 datasource `uid` 누락으로 깨짐

**증상**
Grafana 프로비저닝은 성공하지만 대시보드 패널이 데이터소스를 못 찾아 빈 그래프만 표시.

**원인**
`observability/grafana/provisioning/datasources/datasource.yml`에 `uid`를 지정하지 않아
자동 생성된 uid와 대시보드 JSON이 참조하는 고정 uid(`Prometheus`)가 어긋났다.

**해결**
```yaml
uid: Prometheus  # 대시보드 JSON이 이 uid로 데이터소스를 참조한다
```

**영향 범위**
1.18의 Grafana 대시보드 전체.

**재발 방지**
프로비저닝 datasource와 대시보드 JSON을 같이 커밋할 때는 uid를 명시적으로 고정한다 — 자동
생성값에 의존하지 않는다.

---

### 6. Grafana admin 비밀번호가 compose 파일에 평문 하드코딩

**증상**
`docker-compose.observability.yml`의 `GF_SECURITY_ADMIN_PASSWORD: admin`.

**원인**
로컬 개발 편의를 위한 기본값이 그대로 커밋됨. `.coderabbit.yaml`의 `**/*.yml` 지침("시크릿이
평문으로 들어있지 않은가")에 직접 해당.

**해결**
```yaml
GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?set GRAFANA_ADMIN_PASSWORD in .env}
```
`.env`에 없으면 compose가 즉시 에러를 내도록 강제. `docs/stages/02-monolithic-core.md`의 안내
문구도 함께 갱신.

**영향 범위**
로컬 전용 스택(1단계 observability)이라 운영 노출 위험은 없었지만, 습관으로 굳으면 나중
단계에서 실제 비밀로 반복될 수 있다.

**재발 방지**
compose 파일의 자격 증명은 항상 `${VAR:?message}` 형태로 강제하고 기본값을 주지 않는다.

---

### 7. 벤치마크 스크립트가 고정 `sleep 15`로 앱 기동을 기다림

**증상**
`scripts/benchmark-lock-strategies.sh`/`benchmark-optimistic-retries.sh`가 앱을 백그라운드로
띄운 뒤 무조건 15초를 쉬고 측정을 시작 — 느린 환경에서는 기동이 덜 끝난 상태로 측정이
시작되고, 빠른 환경에서는 불필요하게 느려짐.

**원인**
고정 sleep은 "충분히 길다"를 보장하지 못하면서 항상 그 시간만큼은 낭비한다.

**해결**
`scripts/_wait-for-app.sh`에 공용 함수를 만들어 `/actuator/health`가 `"status":"UP"`을 반환할
때까지 폴링하도록 바꿨다.
```bash
wait_for_app_ready() {
    local url=$1 timeout=$2
    # ... UP 응답까지 폴링, timeout 초과 시 실패
}
```
두 벤치마크 스크립트 모두 `sleep 15`를 이 함수 호출로 교체.

**영향 범위**
1.13/1.14 벤치마크 측정값의 재현성 — 기동이 덜 끝난 상태에서 첫 요청들이 측정에 섞이면
p50/p99가 왜곡된다.

**재발 방지**
새 스크립트에서 앱 기동을 기다릴 때는 고정 sleep 대신 항상 헬스체크 폴링을 쓴다.

---

### 8. `IdempotencyAspect`의 어드바이스 순서가 정의돼 있지 않았음

**증상**
드러난 장애는 아니지만, `IdempotencyAspect`(짧은 트랜잭션으로 즉시 commit해야 하는 레코드
저장)와 Spring 트랜잭션 어드바이저가 둘 다 기본값(`Ordered.LOWEST_PRECEDENCE`)이라 어느 쪽이
바깥에서 실행될지 스펙상 정의돼 있지 않았다.

**원인**
트랜잭션 어드바이저가 바깥이 되면, `around()`가 `proceed()` 호출 전에 실행하는
`saveAndFlush`가 이미 열려 있는 비즈니스 트랜잭션에 합류해버려서 "짧은 트랜잭션으로 즉시
커밋"이라는 전제가 깨지고, 동시 요청이 그 커밋 전 상태를 보게 된다.

**해결**
```java
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotencyAspect {
```
이 애스펙트가 항상 트랜잭션 어드바이저보다 바깥에서 실행되도록 강제.

**영향 범위**
멱등성 2단 방어(부록 A-1) 전체. 순서가 반대로 결정되는 환경에서는 1.9/1.10의 동시 중복요청
테스트가 통과해도 실제로는 의도한 격리를 보장하지 못했을 것.

**재발 방지**
비즈니스 트랜잭션과 별개로 자체 커밋 타이밍을 가져야 하는 애스펙트는 반드시 `@Order`를
명시한다 — Spring의 어드바이저 기본 순서에 기대지 않는다.

---

### 9. `IdempotencyAspect`가 TTL 만료된 `COMPLETED` 레코드를 계속 재현함

**증상**
DB `idempotency_keys` 레코드는 24시간 TTL(`expiresAt`)을 갖지만, 실제로 그 시각이 지나도
삭제/무시하는 로직이 없어 만료된 레코드를 영원히 재현할 수 있었다.

**원인**
`findByIdempotencyKey`로 찾은 레코드가 `COMPLETED`이기만 하면 만료 여부를 보지 않고 그대로
응답을 재현했다.

**해결**
```java
private Optional<IdempotencyRecord> purgeIfExpired(Optional<IdempotencyRecord> found) {
    if (found.isPresent() && isExpired(found.get())) {
        repository.delete(found.get());
        return Optional.empty();
    }
    return found;
}

private static boolean isExpired(IdempotencyRecord record) {
    return record.getStatus() == IdempotencyStatus.COMPLETED && Instant.now().isAfter(record.getExpiresAt());
}
```
조회 경로(`around`의 `existing` 조회, `DataIntegrityViolationException` catch의 winner 조회,
`waitForResult`의 폴링)에 전부 이 필터를 적용해, 만료된 레코드는 "없는 것"으로 취급하고
그 자리에서 삭제한다.

**영향 범위**
멱등성 2단 방어. TTL이 사실상 무의미해지는 문제라 CLAUDE.md의 "Redis TTL은 짧게, DB 레코드는
길게" 정책의 DB 쪽 절반이 지켜지지 않고 있었다.

**재발 방지**
TTL 필드를 추가할 때는 "값을 기록한다"와 "만료를 실제로 강제한다"를 분리해서 확인한다 —
컬럼이 있다고 해서 만료가 자동으로 적용되지 않는다(별도 배치/필터가 필요).

---

### 10. `DistributedLockStockDeductor`가 lease를 명시해 Redisson watchdog을 꺼버림

**증상**
`lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)`처럼 lease를 명시적으로 주고
있었다. DB 커넥션 대기가 겹쳐 내부 트랜잭션이 lease보다 길어지면 락이 조기 만료되고, 다른
스레드가 같은 재고 행에 들어와 Lost Update가 날 수 있는 구조였다.

**원인**
Redisson의 watchdog(락을 쥔 클라이언트가 살아있는 동안 자동으로 락을 연장, 기본 30초마다)은
lease를 명시하지 않았을 때만 동작한다. lease를 주면 watchdog이 꺼지고 그 시간이 지나면
무조건 락이 풀린다.

**해결**
```java
// lease를 명시하지 않는다 — Redisson watchdog가 락을 자동 갱신한다(기본 30초마다 연장).
locked = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
```
`LEASE_SECONDS` 상수 제거.

**영향 범위**
분산 락 전략(1.11-4) 전체. 300-way 경합(1.12) 같은 부하 상황에서 재현 확률이 특히 높다 — DB
커넥션 풀이 좁을수록 대기 시간이 늘어나 lease를 넘길 가능성이 커진다.

**재발 방지**
Redisson `RLock`을 쓸 때 watchdog 자동 연장에 기댈지 명시적 lease를 쓸지는 트레이드오프
(전자: 무한정 길어질 수 있는 대신 안전, 후자: 상한이 명확한 대신 조기 만료 위험)를 의식적으로
선택한다 — 기본값처럼 lease를 습관적으로 채워 넣지 않는다.

---

### 11. `NoLockStockDeductor`가 `queryForObject` 사용 → 0행일 때 404 대신 500

**증상**
존재하지 않는 상품 ID로 조회하면 `EmptyResultDataAccessException`이 그대로 터져 500으로
응답 — `ProductNotFoundException`(404)이 나와야 하는 자리였다.

**원인**
`JdbcTemplate.queryForObject`는 결과가 0행이면 `null`이 아니라
`EmptyResultDataAccessException`을 던진다(흔한 Spring JDBC 함정). 이 사실을 놓치고 null 체크만
하고 있었다.

**해결**
```java
List<Map<String, Object>> rows = jdbcTemplate.queryForList(...);
Map<String, Object> row = DataAccessUtils.singleResult(rows);
if (row == null) {
    throw new ProductNotFoundException(productId);
}
```
`queryForList` + `DataAccessUtils.singleResult`로 교체해 0행을 정상적인 `null`로 받는다.

**영향 범위**
NONE 전략(1.11-1, Lost Update 재현용)에서 존재하지 않는 상품을 조회하는 모든 경로.

**재발 방지**
`JdbcTemplate`에서 "있을 수도 없을 수도 있는 단건 조회"는 `queryForObject`를 쓰지 않는다 —
`queryForList` + `DataAccessUtils.singleResult` 조합을 기본값으로 삼는다.

---

### 12. `MockPgServer`가 테스트에서 종료되지 않아 포트/스레드가 누수됨

**증상**
`MockPgServerTest`/`PaymentIdempotencyConcurrencyTest`/`PaymentIdempotencyRedisDownTest`가
`MockPgServer`를 기동만 하고 종료하지 않았다. 테스트 스위트 전체를 연속 실행하면 열린 포트와
워커 스레드가 계속 쌓인다.

**원인**
`MockPgServer`에 애초에 `stop()`이 없었다 — 시작하는 메서드만 있고 대응하는 정리 메서드가
없는 리소스였다.

**해결**
`MockPgServer`에 `server` 필드와 `stop()`(내부 `HttpServer.stop(0)` + 워커 풀
`shutdownNow()`)을 추가하고, 각 테스트 클래스에 `@AfterAll`로 호출을 연결했다. `forceTimeout`
테스트의 별도 인스턴스(`shortTimeoutServer`)도 `finally`에서 HTTP로 설정을 리셋하던 방식 대신
그냥 `stop()`하도록 바꿨다(어차피 인스턴스를 버리므로 리셋이 무의미했다).

**영향 범위**
로컬/CI에서 이 세 테스트 클래스를 반복 실행할 때의 리소스 누수. 단일 실행에서는 드러나지
않지만 CI가 같은 러너를 재사용하거나 로컬에서 반복 실행할 때 포트 고갈로 이어질 수 있었다.

**재발 방지**
`@BeforeAll`로 리소스를 만들면 반드시 대응하는 `@AfterAll`을 같이 추가한다 — 단명 프로세스인
CI라도 습관을 프로덕션 코드와 동일하게 가져간다.

---

### 13. 동시성 테스트 3건의 스레드풀 크기가 목표 동시 요청 수보다 작음

**증상**
`InventoryConcurrencyTest`(300 요청), `PaymentIdempotencyConcurrencyTest`(100 요청),
`PaymentIdempotencyRedisDownTest`(20 요청) 모두 `Executors.newFixedThreadPool(N)`의 N을
목표 동시 요청 수보다 작게(각각 50, 32, 10) 잡고 있었다. `ready.await(5, TimeUnit.SECONDS)`도
결과를 검증하지 않고 그냥 호출만 했다.

**원인**
풀 크기가 목표보다 작으면, 풀에 들어가지 못하고 큐에 남은 태스크는 앞선 태스크가
`go.await()`에서 블로킹된 채로는 아예 시작조차 못 한다. 그러면 `ready`(태스크 시작을 세는
래치)가 절대 0에 도달하지 못해 매번 5초 타임아웃 뒤에야 다음 단계로 넘어갔고, 그마저도
"동시 N건"이 아니라 실제로는 풀 크기만큼만 동시에 실행되는 셈이었다 — 테스트가 스스로
증명하려는 경합 규모를 만들지 못하고 있었다.

**해결**
세 파일 모두 풀 크기를 목표 동시 요청 수와 동일하게 맞추고(`newFixedThreadPool(N)`의 N == 동시
요청 수), `ready.await`의 결과를 `assertThat(...).isTrue()`로 단언해 타임아웃이 나면 테스트
자체가 명확히 실패하도록 바꿨다(조용히 넘어가지 않게).
```java
assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드가 출발선에 도달해야 함").isTrue();
```

**영향 범위**
1.9/1.10/1.12의 동시성 안전성 주장 전체 — 실제로는 의도한 동시 요청 수보다 적은 규모로만
검증되고 있었다는 뜻이라, 통과했다는 사실 자체의 증명력이 낮아져 있었다.

**재발 방지**
`CountDownLatch` 기반 "출발선 맞추기" 패턴을 쓸 때는 항상 `ExecutorService` 풀 크기를 동시
요청 수와 같게 맞추고, `ready.await`의 반환값을 반드시 assert한다 — 그냥 호출만 하면 타임아웃도
조용히 삼켜진다.

---

### 14. `MockPgClient`가 5xx/빈 응답 바디를 FAILED로 오판할 수 있었음

**증상**
`MockPgServer.handlePayment`가 5xx를 반환할 때는 `{"error": ...}`만 주고 `status` 필드가
없는데, 클라이언트가 이를 억지로 `MockPgResponseBody`로 역직렬화하면 `body.status()`가
`null`이 되어 `"APPROVED".equals(null)`이 `false`로 평가되면서 **승인 여부를 알 수 없는
응답이 FAILED로 단정**돼 버렸다. CLAUDE.md의 "타임아웃된 결제는 FAILED가 아니라 UNKNOWN"
원칙을 정면으로 어기는 지점이었다(5xx는 타임아웃과 마찬가지로 승인 여부 불명 상태).

**원인**
정상 응답(`status` 필드 있음)과 5xx 에러 응답(`status` 필드 없음)의 바디 스키마가 다른데,
같은 역직렬화 경로로 처리하면서 `null` status를 FAILED로 잘못 폴백시켰다.

**해결**
```java
if (res.getStatusCode().is5xxServerError()) {
    return MockPgResult.timedOut();
}
MockPgResponseBody body = res.bodyTo(MockPgResponseBody.class);
if (body == null || body.status() == null) {
    return MockPgResult.timedOut();
}
```
5xx는 body를 읽기 전에 먼저 걸러 `timedOut()`으로 변환하고, 혹시 남는 `null` body/status도
같은 방식으로 안전망을 둬 NPE 대신 UNKNOWN으로 수렴하게 했다.

**영향 범위**
결제 승인 판정 전체. 이 버그가 프로덕션에 있었다면 "PG가 실제로는 처리했을 수도 있는" 5xx
응답을 전부 FAILED로 확정해, 실제로는 승인됐는데 결제가 실패로 기록되는 사고로 이어질 수
있었다(가장 위험한 방향의 오판 — FAILED는 재시도를, UNKNOWN은 재조회를 유도해야 하는데
FAILED로 잘못 확정하면 이미 승인된 결제를 중복 재시도하게 된다).

**재발 방지**
외부 응답의 성공/실패 스키마가 다르면 역직렬화 전에 반드시 상태 코드로 먼저 분기한다 — 실패
응답 바디를 성공 스키마로 강제 역직렬화해 `null` 필드로 흘려보내지 않는다.

---

### 15. 주문/재고 원자성 트레이드오프가 코드에 문서화돼 있지 않았음

**증상**
`OptimisticLockStockDeductor`/`DistributedLockStockDeductor`가 `PROPAGATION_REQUIRES_NEW`로
재고 차감을 독립 커밋하는데, 이게 `OrderService.createOrder`의 바깥 트랜잭션과 원자성이
깨질 수 있다는 사실이 각 파일의 REQUIRES_NEW 주석(재시도 stale read, unlock-before-commit
경합 방지 목적)에만 있었고 정작 그 결과로 생기는 트레이드오프(재고 차감 커밋 후
`orderRepository.save`가 실패하면 재고만 차감되고 주문은 안 만들어짐)는 어디에도 적혀 있지
않았다.

**원인**
REQUIRES_NEW를 쓴 "이유"는 설명돼 있었지만 그로 인한 "대가"는 설명돼 있지 않았다.

**해결**
재설계(예: 2단계 Saga 보상 트랜잭션을 앞당겨 도입) 대신, 이 트레이드오프를 명시적으로 문서화만
했다 — 1단계는 "즉시 차감 모델"이 선행 결정 사항이고, 보상은 2단계 Saga의 몫이라는 로드맵
설계와 일치한다.
```java
// OrderService.createOrder Javadoc에 추가
/**
 * <p><b>알려진 트레이드오프:</b> stockDeductionPort의 OPTIMISTIC/DISTRIBUTED 구현은 내부적으로
 * PROPAGATION_REQUIRES_NEW를 써서 이 메서드의 트랜잭션과 별개로 즉시 커밋한다. 그 결과 재고
 * 차감이 커밋된 *이후* orderRepository.save(order)가 실패하면 재고 차감은 롤백되지 않고
 * 주문만 안 만들어지는 원자성 깨짐이 생길 수 있다. 1단계에서는 이 확률을 감수한다 — 보상
 * (2단계 Saga)이 이 문제의 정식 해법이다.
 */
```
같은 설명을 `OptimisticLockStockDeductor`/`DistributedLockStockDeductor` 클래스 Javadoc에도
상호 참조로 추가.

**영향 범위**
문서만 변경 — 런타임 동작은 그대로다(의도적으로 재설계하지 않음).

**재발 방지**
REQUIRES_NEW 같은 전파 옵션을 도입할 때는 "왜 필요한가"뿐 아니라 "그 대가로 무엇을 잃는가"를
같은 Javadoc에 같이 남긴다.

---

### 16. `PaymentService`의 외부 PG 호출이 `@Transactional` 범위 안에서 일어남

**증상**
`requestPayment` 메서드 전체가 `@Transactional`이었고, 그 안에서 `mockPgClient.requestPayment`
(HTTP 호출)를 그대로 실행했다. `.coderabbit.yaml`의 `**/payment/**` 경로 지침이 정확히 이
패턴을 금지한다 — "외부 PG 호출이 @Transactional 범위 안에서 일어나지 않는가 (외부 I/O가 DB
커넥션을 점유하면 부하 시 풀이 고갈됨)".

**원인**
PENDING 저장 → PG 호출 → 결과 반영을 하나의 트랜잭션으로 묶어서 구현했다. PG 응답이 느려지면
(Mock PG의 `delayMs`/`forceTimeout` 설정으로 1.5에서 이미 재현 가능한 상황) 그 시간만큼 DB
커넥션을 붙든 채 대기하게 된다.

**해결**
트랜잭션을 세 구간으로 쪼갰다.
```java
@Idempotent(key = "#idempotencyKey")
public PaymentResponse requestPayment(String idempotencyKey, RequestPaymentRequest request) {
    OrderView order = orderPort.findOrder(request.orderId());
    validateOrder(order, request);

    Long paymentId = savePending(idempotencyKey, request);          // 짧은 트랜잭션 1

    MockPgResult result = mockPgClient.requestPayment(...);          // 트랜잭션 밖

    return applyResult(paymentId, request.orderId(), result);        // 짧은 트랜잭션 2
}
```
`savePending`/`applyResult`는 각각 `TransactionTemplate`으로 감싼 별도의 짧은 트랜잭션이고,
메서드 자체에는 더 이상 `@Transactional`을 달지 않는다. `@Idempotent` AOP는 `joinPoint.proceed()`
전체를 감쌀 뿐 내부 트랜잭션 경계와 무관하게 동작하므로 이 분리와 독립적으로 계속 성립한다.

**영향 범위**
결제 요청 경로 전체 — 부하 상황에서 PG 응답 지연이 DB 커넥션 풀 고갈로 전이되는 경로를 막는다.
1.9/1.10의 동시성 테스트가 여전히 통과하는지로 회귀를 확인했다(멱등성 보장은 트랜잭션 경계와
무관하게 `@Idempotent` AOP + DB unique 제약이 담당하므로 영향 없음).

**재발 방지**
`.coderabbit.yaml`의 경로별 지침을 새 코드를 짤 때 미리 체크리스트로 참고한다 — 특히
`payment`/`saga` 경로는 외부 I/O와 트랜잭션 경계 관련 규칙이 명시돼 있다.

---

### 17. `PaymentService`가 실제 주문을 검증하지도, 승인 시 주문을 `PAID`로 전이하지도 않았음

**증상**
`requestPayment`가 `request.orderId()`를 그냥 신뢰하고 `Payment` 레코드만 만들었다 — 그
주문이 실제로 존재하는지, 금액/통화가 맞는지, 이미 결제된 주문은 아닌지 전혀 확인하지 않았고,
결제가 APPROVED가 돼도 `Order.status`는 영원히 `CREATED`에 머물렀다.

**원인**
`order`/`payment`가 서로 직접 참조하지 않는다는 패키지 경계 원칙(CLAUDE.md) 때문에 이 연결을
만들려면 `common`을 경유하는 포트가 필요한데, 그 포트가 아직 없었다 — 구현이 빠진 상태였다.

**해결**
기존 `common.catalog.ProductPriceLookup`/`common.inventory.StockDeductionPort`와 동일한
패턴으로 `common.order.OrderPort`(+ DTO `OrderView`)를 새로 만들고 `order` 패키지가 구현
(`OrderPortImpl`)을 제공하도록 했다.
```java
public interface OrderPort {
    OrderView findOrder(Long orderId);
    void markPaid(Long orderId);
}
```
`PaymentService`는 결제 전 `orderPort.findOrder(...)`로 주문을 조회해 이미 결제됨/금액
불일치/통화 불일치를 `PaymentOrderMismatchException`(409)으로 거부하고, PG 승인 시
`orderPort.markPaid(orderId)`를 `applyResult`의 짧은 트랜잭션 안에서 호출해 결제 승인과 주문
`PAID` 전이가 같은 커밋으로 묶이게 했다.

**영향 범위**
결제 요청 API 전체 — 존재하지 않는 주문, 금액이 다른 주문에 대한 결제 요청을 막고, 승인된
결제가 주문 상태에 반영되지 않던 문제를 고쳤다. 관련해서 기존 동시성 테스트
(`PaymentIdempotencyConcurrencyTest`, `PaymentIdempotencyRedisDownTest`)가 존재하지 않는
`orderId=1L`을 그냥 썼던 부분도 실제 `Order`를 미리 저장하도록 함께 고쳤다(요청 금액/통화와
일치시켜서).

**재발 방지**
새 크로스 패키지 참조가 필요해지면 기존 포트(`ProductPriceLookup`, `StockDeductionPort`) 패턴을
그대로 따른다 — 인터페이스+DTO는 `common`에, 구현은 소유 패키지에.

---

## 겪은 문제 — CodeRabbit 수정 커밋이 CI에서 새로 터뜨린 버그

### 18. 스레드풀 크기를 고쳐 "진짜 동시성"이 되자 AspectJ 파라미터 바인딩이 드물게 깨짐

**증상**
16번(스레드풀 크기 수정) 커밋을 푸시한 뒤 CI `build-test`가 실패했다.
`PaymentIdempotencyConcurrencyTest`(100건), `PaymentIdempotencyRedisDownTest`(20건) 둘 다
같은 예외로 실패:
```
java.lang.IllegalStateException: Required to bind 2 arguments, but only bound 1
    (JoinPointMatch was NOT bound in invocation)
    at org.springframework.aop.aspectj.AbstractAspectJAdvice.argBinding(...)
    at org.example.cs_study.payment.PaymentService$$SpringCGLIB$$0.requestPayment(<generated>)
```

**원인**
`IdempotencyAspect`의 포인트컷이 `@Around("@annotation(idempotent)")` — 애노테이션 값을
어드바이스 파라미터로 **바인딩하는** 형태였다. 이 바인딩 방식은 Spring AOP/AspectJ의 알려진
동시성 버그를 갖고 있다: 매칭 결과(`JoinPointMatch`)를 계산·전달하는 내부 경로가 여러 스레드가
같은 메서드를 진짜로 동시에 호출할 때 스레드 세이프하지 않아, 드물게(부하 테스트 기준 대략
수십~수백 건 중 1건 수준) 파라미터 바인딩이 깨진다. 동일 증상이 보고된 바 있다
([resilience4j/resilience4j#919](https://github.com/resilience4j/resilience4j/issues/919) —
`@CircuitBreaker` 같은 다른 애노테이션 바인딩 포인트컷에서도 동시 부하 시 똑같은 예외 메시지).

이 프로젝트에서 이 버그가 지금까지 한 번도 CI를 실패시키지 않았던 이유가 바로 위(13번
항목)에서 고친 그 버그다 — 스레드풀 크기가 목표 동시 요청 수보다 작아서 `PaymentService.
requestPayment()`가 실제로는 풀 크기(32/10)만큼만 동시에 호출되고 있었다. 16번 커밋으로
풀 크기를 실제 목표(100/20)와 맞추면서 처음으로 이 메서드가 "진짜" 그 규모로 동시 호출됐고,
그 순간 이전까지 숨어 있던 AspectJ 레벨 레이스가 CI에서 곧바로 드러났다. 즉 **한 버그(테스트가
실제 동시성을 재현하지 못함)가 다른 버그(AOP 프레임워크의 동시성 결함)를 가리고 있었다.**

**해결**
포인트컷을 바인딩 없는 형태로 바꿨다 — 애노테이션 타입을 FQCN으로 직접 명시하고, 애노테이션
인스턴스는 어드바이스 안에서 리플렉션으로 직접 읽는다. 이러면 AspectJ의 파라미터 바인딩
경로(`argBinding`/`JoinPointMatch`) 자체를 타지 않으므로 이 레이스가 원천적으로 발생할 수 없다.
```java
// before
@Around("@annotation(idempotent)")
public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {

// after
@Around("@annotation(org.example.cs_study.common.idempotency.Idempotent)")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Idempotent idempotent = signature.getMethod().getAnnotation(Idempotent.class);
```

**영향 범위**
`@Idempotent`가 붙은 모든 메서드(현재는 `PaymentService.requestPayment` 하나) — 부하 상황에서
드물게 500 에러로 실패할 수 있었던 잠재적 결함이었다. 발생 확률이 낮아(수백 건 중 1건 수준)
로컬 개발이나 가벼운 테스트에서는 거의 드러나지 않고, 딱 이번처럼 진짜 대량 동시 요청을 보낼 때만
나타난다는 점이 위험하다.

**재발 방지**
`@annotation(paramName)`처럼 애노테이션 값을 파라미터로 바인딩하는 AspectJ 포인트컷은 동시성
테스트로 실제 부하를 걸어봐야 이런 레이스가 드러난다는 걸 이번에 확인했다. 앞으로 이런 바인딩
포인트컷을 새로 추가할 때는: (1) 애노테이션에 값이 필요 없으면 애초에 바인딩하지 않고
`@annotation(FQCN)` + 리플렉션 조회를 기본값으로 쓴다, (2) 부득이 바인딩이 필요하면 최소
1.9/1.10 규모(수십~수백 동시 요청)의 부하 테스트를 반드시 한 번은 통과시켜본다. 동시성 테스트를
작성할 때 "스레드풀 크기가 목표 동시 요청 수와 같은가"(13번 항목)를 먼저 확인해야 하는
이유이기도 하다 — 그게 틀리면 이런 프레임워크 레벨 버그까지 통째로 가려진다.

---

## 겪은 문제 — CodeRabbit 2차 라운드 (18번 항목을 포함한 커밋 이후 재리뷰)

18번 항목을 포함해 CI를 초록불로 만든 커밋을 CodeRabbit이 다시 리뷰하며 5건을 추가로 지적했다.
전부 실제 결함으로 판단해 수정했다 — "heavy lift"로 분류된 항목도 예외를 두지 않았다.

### 19. 낙관적 락 재시도 카운터가 `maxRetries`번을 못 채움 (off-by-one)

**증상**
`maxRetries=1`로 설정하면 첫 충돌에서 재시도를 한 번도 하지 않고 즉시 예외가 새어나간다.
`maxRetries=0`도 마찬가지로 재시도가 전혀 없다.

**원인**
`OptimisticLockStockDeductor.deduct()`의 재시도 루프가 `attempt++`를 먼저 한 뒤
`attempt >= maxRetries`를 검사했다. `maxRetries`는 "최초 시도 이후 몇 번 더 시도할지"인데,
증가와 검사 순서가 바뀌어 있어 항상 한 번의 재시도 기회를 덜 쓰고 예외를 던졌다.

**해결**
```java
if (attempt >= maxRetries) {
    throw e;
}
attempt++;
```
검사를 증가보다 먼저 하도록 순서를 바꿨다 — `maxRetries=3`이면 이제 정확히 초기 시도 1회 +
재시도 3회 = 총 4회 시도한다.

**영향 범위**
1.14(재시도 1/3/5/10 곡선) 측정값 전체 — 이 버그 상태로는 "재시도 N회"라고 라벨을 붙인 실험이
실제로는 N-1회만 재시도해서, 곡선의 각 포인트가 의도한 것보다 한 단계씩 덜 관대한 값을 측정하고
있었다.

**재발 방지**
"증가 후 검사" vs "검사 후 증가"는 경계값(0, 1)으로 반드시 손으로 한 번 확인한다. 이번처럼
"당연히 맞겠지"로 넘어가기 쉬운 자리다.

---

### 20. `OrderView`의 `boolean paid`가 FAILED/CANCELLED 주문을 결제 가능으로 오판

**증상**
`OrderPortImpl.findOrder()`가 `status == PAID`만 `true`로 매핑하고 있었다. `validateOrder()`는
`paid == false`면 무조건 통과시켰으므로, `FAILED`나 `CANCELLED` 상태의 주문도 결제를 계속 진행할
수 있었다 — "PAID가 아니면 결제 가능"은 성립하지 않는 명제인데 코드가 그렇게 가정하고 있었다.

**원인**
주문 상태 4가지(CREATED/PAID/FAILED/CANCELLED) 중 "결제 가능(payable=CREATED만)"과 "이미
결제됨(alreadyPaid=PAID만)"이라는 서로 다른 두 조건을 `boolean paid` 하나로 뭉뚱그렸다.

**해결**
`OrderView`를 `boolean paid` 대신 `boolean payable`(=CREATED), `boolean alreadyPaid`(=PAID)
두 필드로 나눴다. `order.OrderStatus` 열거형 자체를 노출하는 방법도 있었지만(CodeRabbit의
1차 제안), 그러면 `payment` 패키지가 `common.order.OrderView`를 통해 간접적으로
`order.OrderStatus` 타입을 다시 알아야 해서 하위 패키지 직접 의존 금지 원칙(CLAUDE.md)이
느슨해진다 — 그래서 의미별로 분리된 boolean 두 개를 택했다.
```java
if (order.alreadyPaid()) {
    throw new PaymentOrderMismatchException("이미 결제 완료된 주문입니다: orderId=" + order.orderId());
}
if (!order.payable()) {
    throw new PaymentOrderMismatchException("결제할 수 없는 상태의 주문입니다: orderId=" + order.orderId());
}
```

**영향 범위**
결제 요청 API 전체 — FAILED/CANCELLED 주문에 결제를 다시 붙일 수 있었던 심각한 결함(CodeRabbit이
🔴 Critical로 분류).

**재발 방지**
다치(多値) 상태를 boolean 하나로 압축할 때는 "false가 의미하는 모든 경우"를 나열해보고 그중
잘못 포함된 상태가 없는지 확인한다.

---

### 21. 서로 다른 멱등 키로 같은 주문에 동시 결제 — TOCTOU

**증상**
`PaymentService.validateOrder()`는 읽기 시점 검사다. 서로 다른 멱등키를 쓴 두 요청이 동시에
같은 주문을 조회하면 둘 다 "결제 가능"으로 통과해 각자 PG를 호출할 수 있었다 —
멱등성 방어(같은 키의 중복 요청)는 있었지만, "같은 주문에 대한 서로 다른 키의 동시 결제"는
막는 장치가 없었다.

**원인**
주문을 "선점"하는 원자적 단계 없이, 읽기(주문 조회) → 쓰기(결제 저장)가 별도 스텝으로 분리돼
있어 그 사이에 경쟁이 들어갈 수 있었다(Time-Of-Check to Time-Of-Use).

**해결**
멱등성 2단 방어와 같은 패턴(부록 A-1: 애플리케이션 체크 + DB unique 제약)을 여기도 적용했다.
`payments(order_id)`에 "활성 상태(PENDING/APPROVED/UNKNOWN)"에 한정한 부분 유니크 인덱스를
추가하고, 그 제약 위반을 도메인 예외로 번역한다.
```sql
CREATE UNIQUE INDEX ux_payments_active_order ON payments (order_id)
    WHERE status IN ('PENDING', 'APPROVED', 'UNKNOWN');
```
```java
try {
    return transactionTemplate.execute(status -> {
        Payment payment = new Payment(...);
        paymentRepository.saveAndFlush(payment); // 즉시 flush해야 여기서 위반이 드러난다
        return payment.getId();
    });
} catch (DataIntegrityViolationException e) {
    throw new PaymentOrderMismatchException("이미 처리 중이거나 완료된 결제가 있는 주문입니다: orderId=" + request.orderId());
}
```
FAILED/CANCELLED는 종결 상태라 인덱스 조건에서 제외했다 — 실패한 결제 이후 같은 주문으로 다시
결제를 시도하는 정상 흐름을 막지 않기 위해서다.

**영향 범위**
결제 요청 API 전체 — 이 방어가 없으면 한 주문에 결제가 두 번 승인될 수 있었다(가장 치명적인
유형의 결제 버그).

**재발 방지**
"동시에 두 요청이 같은 자원을 먼저 차지하려 competing한다"는 패턴을 발견하면, 애플리케이션
레벨 읽기 검사만으로는 항상 TOCTOU 틈이 남는다고 가정하고 DB 제약(유니크 인덱스,
`SELECT ... FOR UPDATE` 등) 같은 원자적 방어를 반드시 같이 놓는다.

---

### 22. 멱등 키를 재사용하면서 요청 본문을 바꿔도 그대로 재현됨

**증상**
동일한 멱등 키로 `orderId`, `amount`, `currency`를 바꿔서 다시 요청해도
`IdempotencyAspect`는 키만 보고 이전 응답을 그대로 재현했다 — `PaymentService`의 검증 로직
자체를 다시 타지 않는다.

**원인**
2단 방어(Redis SETNX + DB unique 제약)는 "같은 키의 중복 요청"만 막도록 설계돼 있었고, "같은
키인데 본문이 다른 요청"은 애초에 고려 대상이 아니었다.

**해결**
최초 요청의 메서드 인자를 SHA-256으로 해시한 지문(fingerprint)을 `idempotency_keys` 테이블에
같이 저장하고, 같은 키가 재사용될 때(진행 중 대기 경로 포함) 지문을 비교해서 다르면
`IdempotencyKeyConflictException`(409)으로 거부한다.
```sql
ALTER TABLE idempotency_keys ADD COLUMN request_fingerprint VARCHAR(64) NOT NULL;
```
Redis 캐시 값에도 지문을 같이 실어서(`COMPLETED:<64자 지문><json>`), Redis 캐시 히트 경로도
DB 폴링 경로와 동일하게 지문을 검증하게 했다 — 안 그러면 Redis가 이미 완료 응답을 캐시해둔
순간에는 지문 검증 없이 그대로 재현되는 구멍이 남는다.

**영향 범위**
멱등성 2단 방어 전체 — 클라이언트가 실수로(또는 악의적으로) 같은 멱등 키를 다른 주문/금액에
재사용해도 걸러지지 않던 결함.

**재발 방지**
멱등 키 설계 시 "같은 키 = 같은 요청"이라는 전제를 코드가 실제로 강제하는지 확인한다 — 키만
보고 신뢰하면 이런 종류의 재사용 공격/실수를 막을 수 없다.

---

### 23. 벤치마크 스크립트가 `wait_for_app_ready` 타임아웃 시 백그라운드 프로세스를 못 지움

**증상**
`set -euo pipefail` 아래에서 `wait_for_app_ready`가 타임아웃으로 실패(`exit 1`)하면 스크립트가
그 즉시 종료돼, 뒤에 있던 `kill "${APP_PID}"`에 도달하지 못하고 `bootRun` 프로세스가 백그라운드에
남는다.

**원인**
정리(cleanup) 로직이 정상 흐름의 마지막에만 있고, 조기 종료 경로(`set -e`로 인한 즉시 exit)에는
없었다.

**해결**
`APP_PID`를 할당한 직후 `EXIT` 트랩을 등록해서, 어떤 경로로 스크립트가 끝나든 프로세스 정리가
보장되게 했다.
```bash
APP_PID=$!
trap 'kill "${APP_PID}" 2>/dev/null || true' EXIT
wait_for_app_ready
```
`scripts/benchmark-lock-strategies.sh`, `scripts/benchmark-optimistic-retries.sh` 둘 다 동일하게
적용.

**영향 범위**
로컬에서 벤치마크 스크립트를 반복 실행할 때의 프로세스 누수 — 타임아웃이 나면 죽은 줄 알았던
`bootRun`이 계속 포트를 붙들고 있어 다음 실행이 포트 충돌로 실패할 수 있었다.

**재발 방지**
`set -e` 스크립트에서 백그라운드 프로세스를 띄우면 PID를 할당한 직후 바로 `trap ... EXIT`를
등록하는 걸 기본 패턴으로 삼는다 — 정상 종료 시의 명시적 `kill`과 트랩은 중복돼도 무해하다.

---

## 요약

| # | 분류 | 파일 | 한 줄 요약 |
|---|---|---|---|
| 1 | CI | `Inventory.java` | `@PrePersist` 누락으로 INSERT 실패 |
| 2 | CI | `InventoryConcurrencyTest.java` | 낙관적 락 300-way 경합에서 재시도 소진 (테스트 스코프) |
| 3 | CI | `PaymentIdempotencyRedisDownTest.java` | Hibernate AssertionFailure, 비재현 — 계측만 강화 |
| 4 | 리뷰 | `Order/Payment/GlobalExceptionHandler` | 상태 전이 예외를 내부 버그 예외와 분리 |
| 5 | 리뷰 | `datasource.yml` | Grafana datasource uid 고정 |
| 6 | 리뷰 | `docker-compose.observability.yml` | Grafana 비밀번호 하드코딩 제거 |
| 7 | 리뷰 | 벤치마크 스크립트 2개 | 고정 sleep → 헬스체크 폴링 |
| 8 | 리뷰 | `IdempotencyAspect.java` | 어드바이스 순서 명시 (`@Order(HIGHEST_PRECEDENCE)`) |
| 9 | 리뷰 | `IdempotencyAspect.java` | TTL 만료 레코드 재현 차단 |
| 10 | 리뷰 | `DistributedLockStockDeductor.java` | lease 제거, watchdog 자동 연장 사용 |
| 11 | 리뷰 | `NoLockStockDeductor.java` | `queryForObject` → `queryForList`+`singleResult` |
| 12 | 리뷰 | `MockPgServer.java` + 테스트 3개 | 서버 `stop()` 추가, `@AfterAll` 연결 |
| 13 | 리뷰 | 동시성 테스트 3개 | 스레드풀 크기를 동시 요청 수와 일치, latch 결과 assert |
| 14 | 리뷰 | `MockPgClient.java` | 5xx/빈 body를 UNKNOWN으로 (FAILED 오판 방지) |
| 15 | 리뷰 | `OrderService`/락 구현체 2개 | 주문·재고 원자성 트레이드오프 문서화 |
| 16 | 리뷰 | `PaymentService.java` | PG 호출을 트랜잭션 밖으로 분리 |
| 17 | 리뷰 | `PaymentService.java` + `common.order.*` | 주문 검증 + 승인 시 `markPaid()` 연결 |
| 18 | CI 재실패 | `IdempotencyAspect.java` | 애노테이션 바인딩 포인트컷의 AspectJ 동시성 버그 → 바인딩 없는 형태로 전환 |
| 19 | 리뷰(2차) | `OptimisticLockStockDeductor.java` | 재시도 카운터 off-by-one → 검사를 증가보다 먼저 |
| 20 | 리뷰(2차) | `OrderView.java`/`OrderPortImpl.java`/`PaymentService.java` | `boolean paid` → `payable`+`alreadyPaid`로 분리 (FAILED/CANCELLED 오판 수정) |
| 21 | 리뷰(2차) | `V2__domain_schema.sql`/`PaymentService.java` | 주문별 활성 결제 부분 유니크 인덱스로 TOCTOU 차단 |
| 22 | 리뷰(2차) | `IdempotencyAspect.java`/`IdempotencyRecord.java` | 멱등 키 재사용 시 요청 본문 지문(SHA-256) 검증 |
| 23 | 리뷰(2차) | 벤치마크 스크립트 2개 | `wait_for_app_ready` 타임아웃 시에도 `APP_PID` 정리 (EXIT 트랩) |
