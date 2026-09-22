# 2.1 — Gradle 멀티모듈 전환 트러블슈팅 / 설계 결정

모놀리식 단일 모듈(`cs_study`)을 8개 Gradle 모듈(`common-event`, `common-web`,
`common-idempotency`, `mock-pg-server`, `order-service`, `payment-service`,
`inventory-service`, `notification-service`)로 분리한 작업(이슈 #42)에서 내린 판단과,
아직 해소되지 않은 상태를 기록합니다.

환경: Spring Boot 4.1.1 / Java 21 / Gradle 9.7.1 — 이 세션은 Docker가 없는 원격 컨테이너라
컴파일/패키징(`compileJava`/`compileTestJava`/`assemble`)과 Docker 불필요 테스트
(`MockPgServerTest`/`OrderStatusTest`/`PaymentStatusTest`)까지만 로컬에서 실측 검증했다.
Testcontainers 기반 통합 테스트는 CI(ubuntu-latest, Docker 내장)에서 실제로 2건 잡아냈다
(아래 4, 5번) — 로컬 컴파일만으로는 못 잡는 런타임 전용 버그였다.

---

### 1. 서비스 간 동기 호출 2곳을 걷어냈다 — `createOrder`/`requestPayment`가 일시적으로 미구현이다

**배경**

의존성 분석(Explore 조사) 결과, 프로덕션 코드에서 `order`/`payment`/`inventory`가 서로 직접
import하는 곳은 0건이었다 — 전부 `common/` 포트 인터페이스로만 연결돼 있었다:

- `order` → `inventory`: `ProductPriceLookup`(가격 조회), `StockDeductionPort`(재고 차감).
  `OrderService.createOrder`가 한 트랜잭션에서 둘 다 호출했다.
- `payment` → `order`: `OrderPort`(주문 조회 `findOrder` + 결제 승인 시 `markPaid`).
  `markPaid`는 결제 승인과 같은 트랜잭션에서 커밋됐다(`PaymentService.applyResult`).

멀티모듈로 쪼개면 이 인터페이스들은 한쪽 서비스에만 존재하게 된다(예: `StockDeductionPort`는
`inventory-service`에만) — 로드맵 2단계 개요가 경고한 "모듈 간 `project()` 의존을 걸면 배포가
묶여서 MSA가 안 된다"를 피하려면 서비스 모듈끼리 서로를 참조하면 안 된다(2.3).

**결정**

두 호출 지점을 삭제하고, 대신 `UnsupportedOperationException`(주문 생성) 또는 TODO 주석(결제
승인 후 주문 상태 전이)으로 명시했다 — 거짓으로 동작하는 척 하지 않는다:

- `OrderService.createOrder`: 가격 정보가 없으면 `totalAmount`를 계산할 방법이 없어 대체
  구현이 불가능하다. `UnsupportedOperationException`을 던진다.
- `PaymentService.requestPayment`: 주문 검증(`validateOrder`)과 `markPaid` 호출을 제거했다.
  결제 자체(멱등성, Mock PG 연동, 상태 전이)는 주문 상태와 독립적으로 계속 동작한다 —
  주문-결제 정합성만 일시적으로 빠졌다.

두 흐름 모두 2-B(2.6~2.14, Kafka Saga)에서 이벤트 기반으로 재구현한다.

**영향 범위**

- `OrderController`의 주문 생성 API는 호출 시 500(`UnsupportedOperationException`)을 반환한다.
- `PaymentIdempotencyConcurrencyTest`/`PaymentIdempotencyRedisDownTest`는 더 이상 실제 주문을
  시드하지 않는다(임의 `orderId`로 충분) — 멱등성만 검증하므로 동작에 영향 없음. 이 두 테스트가
  `order.domain.Order`/`order.repository.OrderRepository`를 직접 import하던 것(모듈 분리 전
  이미 존재하던, 테스트 한정 계층 위반)도 이 김에 제거됐다.

---

### 2. 벤치마크 스크립트/문서 9종이 stale 상태다 — 지금 고치지 않는다

`scripts/measure-baseline.{sh,ps1}`, `scripts/benchmark-lock-strategies.{sh,ps1}`,
`scripts/benchmark-optimistic-retries.{sh,ps1}`, `benchmarks/{01,02,03}-*.md`는 전부
`./gradlew bootRun`(단일 프로세스, 포트 8080) 가정으로 작성됐다.

**결정**: 지금 포트/명령어만 고치지 않는다. 이 스크립트들이 측정하는 흐름(주문 생성 → 결제) 자체가
위 1번 항목으로 일시 미구현이라, 지금 손봐도 2-B에서 실제 흐름이 다시 바뀌면 또 고쳐야 한다 —
중복 작업이다. 2-B에서 Kafka Saga로 흐름이 안정된 뒤 한 번에 재작성한다(2.20 "1단계 대비 성능
비교"와 자연스럽게 묶인다).

**영향 범위**: 이 9개 파일은 2.1 병합 이후 그대로 실행하면 실패한다(포트 불일치 + 미구현 API).
2-B 완료 전까지는 참고용 과거 기록으로만 취급할 것.

---

### 3. Jackson 3 아티팩트 좌표가 groupId까지 바뀌었다

**증상**: `mock-pg-server` 모듈(Boot 플러그인 미적용, 순수 `application` 플러그인)에
`com.fasterxml.jackson.core:jackson-databind`를 선언했더니 `tools.jackson.databind.json`
패키지를 찾을 수 없다는 컴파일 에러가 났다.

**원인**: Boot 4.1.1이 관리하는 Jackson 3.1.5부터 `databind`/`core` 모듈의 groupId가
`tools.jackson.core`로 바뀌었다(`jackson-annotations`만 기존 `com.fasterxml.jackson.core`에
남음). Boot 플러그인을 적용한 모듈은 `spring-boot-jackson` 스타터가 올바른 좌표를 자동으로
끌고 오지만, Boot 플러그인이 없는 순수 라이브러리 모듈은 좌표를 직접 맞춰야 한다.

**해결**: `implementation("tools.jackson.core:jackson-databind")`로 변경. 버전은 루트
`build.gradle.kts`의 `io.spring.dependency-management` BOM 임포트(`spring-boot-dependencies:
4.1.1`)가 관리하므로 버전 명시가 필요 없다.

**영향 범위**: Boot 플러그인 없이 Jackson을 직접 쓰는 모듈(지금은 `mock-pg-server`만 해당)
한정. 향후 같은 성격의 모듈을 추가할 때 재발 가능성 높음 — 이 문서를 먼저 확인할 것.

---

### 4. AspectJ 포인트컷 문자열에 박힌 전체 클래스 경로는 컴파일러가 못 잡는다

**증상**: CI `build-test`(order-service)에서 `QuietFailureRegressionTest`의
`aop_어드바이스가_실제로_프록시를_가로챈다()`가 `expected: 1, but was: 0`로 실패.

**원인**: 이 테스트를 `org.example.cs_study`에서 `org.example.cs_study.order`로 옮기면서
`package` 선언은 IDE/도구로 쉽게 바꿨지만, 내부 `CountingAspect`의
`@Around("execution(* org.example.cs_study.QuietFailureRegressionTest.AopTarget.ping(..))")`
포인트컷 표현식 안에 있던 전체 클래스 경로는 **문자열**이라 컴파일러가 못 잡는다. 포인트컷이
실제(이동 후) 클래스와 매치되지 않아 어드바이스가 전혀 안 걸렸는데도 빌드는 조용히 성공했다.

**해결**: 포인트컷 문자열을 `org.example.cs_study.order.QuietFailureRegressionTest...`로
수정. 같은 패턴(전체 패키지 경로를 문자열로 참조)이 더 있는지 저장소 전체를 검색해 확인했고,
`scanBasePackages`/`IdempotencyAspect`의 `@annotation(...)` 등 나머지는 전부 올바른 경로였다.

**영향 범위**: 패키지 이동이 껴 있는 리팩터링 전반. 이런 문자열 리터럴은 `git mv` +
`package` 선언 일괄 치환으로는 절대 안 잡힌다 — 이동할 때마다
`org\.example\.cs_study\.` 문자열 검색을 습관화할 것.

---

### 5. `@SpringBootApplication(scanBasePackages=...)`는 `@EnableJpaRepositories`/`@EntityScan`엔 적용되지 않는다

**증상**: CI `build-test`(payment-service)에서 `PaymentIdempotencyConcurrencyTest`/
`PaymentIdempotencyRedisDownTest`(둘 다 `@SpringBootTest`) 컨텍스트 로딩이
`NoSuchBeanDefinitionException: ... IdempotencyRecordRepository`로 실패.

**원인**: `PaymentServiceApplication`은 `org.example.cs_study.payment` 패키지에 있고,
`@SpringBootApplication(scanBasePackages = {"...payment", "...common"})`로 컴포넌트 스캔
범위를 넓혀뒀다. 하지만 Spring Data JPA의 `@EnableJpaRepositories`(및 `@EntityScan`) 자동
설정은 `scanBasePackages`가 아니라 **메인 애플리케이션 클래스의 패키지**(`AutoConfigurationPackages`)
를 기본 스캔 범위로 쓴다 — 서로 다른 메커니즘이다. `common-idempotency`의
`IdempotencyRecord`(엔티티)/`IdempotencyRecordRepository`가 `org.example.cs_study.common`에
있어 이 기본 범위 밖이었고, 조용히 빈 등록에서 빠졌다.

**해결**: `PaymentServiceApplication`에 `@EnableJpaRepositories(basePackages = {...})`와
`@EntityScan(basePackages = {...})`을 명시적으로 추가해 `payment`/`common` 둘 다 포함시켰다.

**부수 발견**: Boot 4에서 `@EntityScan`의 패키지가
`org.springframework.boot.autoconfigure.domain`에서
`org.springframework.boot.persistence.autoconfigure`로 이동했다(자동설정이 기능별 모듈로
쪼개진 결과 — 앞선 3번 Jackson 건과 같은 계열의 변화).

**영향 범위**: 지금은 `payment-service`(← `common-idempotency`)만 해당. 앞으로 어떤
서비스든 자신의 패키지 밖(`common-*`)에 있는 `@Entity`/`@Repository`를 쓰게 되면 똑같이
재발한다 — 새 서비스 Application 클래스를 만들 때마다 확인할 것. `compileJava`로는 절대
못 잡는다(런타임 컨텍스트 로딩 시점 오류), CI의 `@SpringBootTest`가 유일한 방어선이었다.

---

### 6. 공유 DB에서 3개 서비스가 완전히 동일한 Flyway 마이그레이션을 공유 이력으로 실행한다 — 지금은 안전하지만 2.2 전에 반드시 알아야 함

CodeRabbit이 지적한 내용([PR #46 리뷰](https://github.com/JoJimi/payment-lab/pull/46))을 검토하고
지금 당장은 고치지 않기로 판단한 근거를 남긴다.

**현재 상태**: `order-service`/`payment-service`/`inventory-service` 셋 다
`payment_lab_dev`(같은 물리 DB)를 보고, `spring.flyway.table`을 지정하지 않아 기본
`flyway_schema_history`도 공유한다. 세 서비스의 `V1__init.sql`/`V2__domain_schema.sql`은
지금 완전히 동일한 파일이다. 그래서 가장 먼저 뜨는 서비스가 전체 스키마(products/inventory/
orders/payments/idempotency_keys/outbox)를 만들고, 나머지 둘은 같은 이력을 보고 "이미
적용됨"으로 건너뛴다 — 지금은 **의도적으로 안전한 상태**다.

**검토한 대안과 기각 이유**:
- *서비스별로 `spring.flyway.table` 이름을 분리한다* → 겉보기엔 깔끔하지만 틀렸다. 세 서비스가
  각자 빈 이력 테이블을 갖게 되므로, 가장 먼저 뜬 서비스가 이미 만든 테이블을 나머지 두 서비스가
  "아직 안 만들어짐"으로 착각해 같은 `CREATE TABLE`을 다시 실행 → 매번 "already exists" 에러로
  기동 실패. 오히려 지금보다 나쁜 상태를 만든다.
- *payment/inventory-service의 Flyway를 비활성화하고 order-service만 소유자로 둔다* → 로컬
  docker-compose의 장수命 DB에는 통하지만, **테스트가 깨진다**. `@Testcontainers`를 쓰는 각
  테스트 클래스는 완전히 새 빈 Postgres 컨테이너를 혼자 띄운다 — 그 안에서는 "다른 서비스가 먼저
  떠서 만들어준 테이블"이 애초에 존재하지 않는다. Flyway를 비활성화한 서비스의 테스트는 스키마가
  하나도 없는 채로 시작해 전부 실패한다(이번 PR에서 바로 이 실수를 할 뻔하다가 CI에서 통과하던
  build-test를 또 깨뜨릴 뻔해서 되돌렸다).

**결론**: 진짜 해법은 서비스별 DB/스키마 분리(2.2, 이슈 #43)뿐이다. 그때는 각 서비스의
마이그레이션 파일 자체를 자신이 소유한 테이블만 남기도록 쪼개야 한다 — 지금처럼 동일한
V1/V2를 유지한 채 이력 테이블만 분리하는 절반짜리 수정은 위 두 가지 이유로 하지 않는다.

**진짜 위험 구간**: 2.2 작업 중 세 서비스의 V1/V2를 서로 다르게 고치는 **중간 단계**. 그
순간부터 공유 이력을 보는 서비스들이 서로 다른 체크섬을 보고할 수 있다. 2.2 PR에서는 이 전환을
한 커밋 안에서 원자적으로 끝내거나(모든 서비스의 마이그레이션을 동시에 분리), 로컬 dev DB를
`docker compose down -v`로 초기화하고 서비스별 DB/스키마로 새로 시작하는 방법 중 하나를 써야
한다 — 2.2 착수 시 이 문서를 먼저 볼 것.

---

### 7. 결제 API를 "조용히 동작"에서 "명시적으로 거부"로 재설계 — `OrderValidator` 포트 + 멱등성 테스트 재배치

CodeRabbit이 [PR #46 리뷰](https://github.com/JoJimi/payment-lab/pull/46)에서 지적한 4번째
사항(§1과 연결): `PaymentService.requestPayment`가 주문 검증 없이 결제를 승인 처리하면
존재하지 않는/금액이 안 맞는 주문에 대해 `payments` 행이 쌓이는 데이터 무결성 문제가 생긴다.
1차 대응(§1)에서는 "결제 자체는 계속 동작하게 두자"고 판단했었는데, 사용자 검토 후 **명시적
거부로 재설계**하기로 했다.

**설계**: `payment-service` 안에 `OrderValidator` 포트(로컬 인터페이스, 모듈 간 아님)를
새로 두고, 유일한 구현체 `UnimplementedOrderValidator`가 호출되면 항상
`BusinessException(ErrorCode.NOT_IMPLEMENTED)`를 던진다. `requestPayment`는 이걸 메서드
맨 앞에서 호출한다. 2-B에서 order-service와의 실제 연동(Kafka 이벤트 왕복 또는 REST)이
생기면 `UnimplementedOrderValidator`를 실제 구현체로 교체하기만 하면 되고,
`PaymentService`는 손댈 필요가 없다 — 이 프로젝트가 이미 쓰던 포트 패턴(`StockDeductionPort`,
`ProductPriceLookup`, `OrderPort`)과 동일한 모양이다.

**부수 효과 — 멱등성 테스트를 소유 모듈로 옮김**: `requestPayment`가 이제 항상 거부되므로,
결제 흐름에 얹혀 있던 기존 동시성 테스트(로드맵 1.9/1.10,
`PaymentIdempotencyConcurrencyTest`/`RedisDownTest`)로는 더 이상 "동시 요청 dedup"을 증명할
수 없다(승인이라는 결과 자체가 없어졌으므로). `IdempotencyAspect`/`IdempotencyRecord`는
애초에 "도메인 중립"이라고 스스로 문서화된 컴포넌트라(`IdempotencyRecord.java` Javadoc),
그 계약을 검증하는 테스트도 특정 소비자가 아니라 소유 모듈(`common-idempotency`)이 들고
있는 게 맞다고 판단했다:

- `common-idempotency`에 `IdempotencyAspectConcurrencyTest`/`IdempotencyRedisDownTest`를
  새로 추가 — 트리비얼한 카운터 메서드를 대상으로 원래 1.9/1.10과 같은 동시성 시나리오(100건
  동시 요청 → 실행 1회, Redis 다운 시 DB 2차 방어)를 재현한다. 이 모듈은 지금까지 자기 자신의
  테스트가 하나도 없었다(Explore 조사로 확인) — 이번에 처음 생긴다.
- payment-service 쪽 두 테스트는 범위를 좁혀서 남긴다: (1) 거부가 `OrderValidator` 게이트에서
  일관되게 발생하는지, (2) 거부된 요청이 idempotency 레코드를 "진행 중"으로 영구히 붙잡지
  않는지(같은 키로 재시도해도 매번 독립적으로 거부되는지 — `IdempotencyAspect`가 예외를
  캐시하지 않고 IN_PROGRESS 마킹을 지우는 동작의 payment-service 통합 관점 확인).

**영향 범위**: `PaymentController`(`POST /api/payments`)는 지금 항상 501을 반환한다 —
2-B 전까지는 의도된 동작이다. `common-idempotency`가 처음으로 Testcontainers(Postgres+Redis)
의존 테스트를 갖게 돼 `gradle.lockfile` 재생성이 필요했다.

### 8. `common-idempotency` 자체 테스트가 CI에서 `MeterRegistry` 빈을 못 찾아 실패했다

`IdempotencyAspectConcurrencyTest`/`IdempotencyRedisDownTest`(§7에서 추가)는 최소 구성의
`@SpringBootApplication static class TestApp {}`을 컨텍스트로 쓴다. `IdempotencyAspect`는
생성자에서 `MeterRegistry`(1.19 커스텀 메트릭용)를 주입받는데, CI에서 두 테스트 모두
`UnsatisfiedDependencyException: No qualifying bean of type
'io.micrometer.core.instrument.MeterRegistry'`로 컨텍스트 로딩부터 실패했다.

원인: `common-idempotency/build.gradle.kts`는 `io.micrometer:micrometer-core`(클래스만)에만
의존한다 — `MeterRegistry` 빈을 실제로 자동 구성해주는 건 Spring Boot Actuator의 메트릭
자동 구성(`spring-boot-starter-actuator`)이다. 지금까지 이 애스펙트가 문제없이 동작했던
유일한 이유는 실제 소비 서비스인 `payment-service`가 `spring-boot-starter-actuator` +
`micrometer-registry-prometheus`를 물고 있었기 때문이다(§7 전까지는 `payment-service`의
`@SpringBootTest`들이 유일한 통합 테스트였다). `common-idempotency`가 처음으로 독립된
트리비얼 테스트 앱을 갖게 되면서(§7) 이 암묵적 의존이 드러났다.

수정: `common-idempotency/build.gradle.kts`의 `testImplementation`에
`org.springframework.boot:spring-boot-starter-actuator`를 추가했다 — 실제 소비 서비스와
동일한 방식으로 이 모듈의 테스트 컨텍스트에도 `MeterRegistry` 빈을 공급한다. 프로덕션
`implementation` 의존성은 건드리지 않았다(이 모듈 자체는 여전히 actuator를 강제하지 않고,
소비 서비스가 원하면 붙이는 구조를 유지).

### 9. `MeterRegistry` 고치고 나니 `IdempotentCounterService` 빈 자체가 안 잡혔다 — Spring Boot Test의 `TestTypeExcludeFilter`

§8을 고쳐 CI에 다시 올렸더니(`b268e7e`) 같은 두 테스트가 다른 오류로 또 실패했다:

```
NoSuchBeanDefinitionException: No qualifying bean of type
'...IdempotencyAspectConcurrencyTest$IdempotentCounterService' available
```

이번엔 `IdempotencyAspect` 빈 생성 자체가 실패한 게 아니라(스택트레이스에 `idempotencyAspect`
언급이 아예 없다), 애초에 `IdempotentCounterService` 빈이 컨테이너에 등록조차 안 됐다.
`TestApp`(`@SpringBootApplication`, `classes=`로 직접 넘김)은 정상 등록됐는데, 같은 패키지에
`@Component`로 선언한 `IdempotentCounterService`(컴포넌트 스캔으로 찾아줄 거라 가정)만 빠졌다.

Docker 없이 로컬에서 원인을 확정하려고, `common-idempotency`에 H2 인메모리 DB +
`localhost:16379`(연결 안 되는 더미 포트)를 쓰는 임시 스크래치 테스트를 만들어 똑같은
`TestApp`/`@Component` 중첩 클래스 패턴을 재현했다 — Testcontainers 없이도 100% 재현됐다.
`ctx.getBeanDefinitionNames()`로 등록된 빈 목록을 찍어보니 `TestApp`만 있고
`IdempotentCounterService`는 아예 없었다.

**근본 원인**: Spring Boot Test는 `@SpringBootTest`가 컴포넌트 스캔을 돌릴 때
`src/test` 소스셋에서 컴파일된 클래스를 기본적으로 스캔 대상에서 제외한다
(`TypeExcludeFilter`/내부적으로 등록되는 test-exclude 필터 — 테스트 픽스처/헬퍼 클래스가
의도치 않게 실제 빈으로 등록되는 사고를 막기 위한 안전장치, 대부분의 프로젝트에서
`@SpringBootTest` 자신과 같은 패키지에 컴포넌트 스캔 대상 앱이 있는 흔한 구조를 보호한다).
`TestApp`이 등록된 건 컴포넌트 스캔으로 "찾아서"가 아니라 `@SpringBootTest(classes=...)`에
직접 나열해서(명시적 import) 등록된 것이었다 — 즉 이 제외 필터를 원래부터 타지 않는 경로였다.
`IdempotentCounterService`는 스캔에만 의존했으니 같은 이유로 조용히 빠졌다.

**수정**: `IdempotentCounterService`도 `TestApp`과 함께 `classes` 배열에 명시적으로
나열했다 — `classes = {X.TestApp.class, X.IdempotentCounterService.class}`. 컴포넌트
스캔에 기대지 않고 두 클래스 모두 직접 import하는 경로로 등록되므로 제외 필터를 타지 않는다.

**교훈**: `@SpringBootTest`에 중첩 `@Component` 픽스처를 쓸 때 "같은 패키지니까 컴포넌트
스캔이 찾아주겠지"라고 가정하면 안 된다 — `src/test`의 클래스는 기본적으로 스캔 제외 대상이다.
컨텍스트에 넣고 싶은 테스트 전용 빈은 항상 `classes=`(또는 `@Import`)에 명시적으로 올려야 한다.

### 10. 서비스별 DB 분리 (2.2) — 공유 Flyway 이력 리스크를 근본 해결

§6에서 지적된 "3개 서비스가 완전히 동일한 Flyway 마이그레이션을 공유 이력으로 실행" 문제를
진짜 해법(서비스별 DB 분리)으로 해소했다. README 방침대로 Order/Payment는 각자 별도 DB,
Inventory/Notification은 같은 DB를 공유하는 구조로 갔다.

**변경 내용**:
- `docker-compose.yml`의 단일 `postgres` 서비스를 `postgres-order`(5432) /
  `postgres-payment`(5433) / `postgres-inventory`(5434) 3개로 분리. 16GB RAM 예산 안에서
  `max_connections`/`shared_buffers`를 줄여 인스턴스 3개를 유지했다(부록 B).
- 각 서비스 `application-dev.yml`의 datasource URL을 각자 DB로 갱신.
- Flyway `V2__domain_schema.sql`을 서비스별 실제 소유 테이블만 남기도록 분리:
  - `order-service`: `orders` + `outbox`(Order Service가 Saga 오케스트레이터 겸 이벤트 발행자)
  - `payment-service`: `payments` + `idempotency_keys`(`common-idempotency`의 유일한 현재
    소비자) + `outbox`
  - `inventory-service`: `products` + `inventory` + `outbox`
  - `notification-service`는 아직 JPA 엔티티가 없어(Explore 확인) datasource 자체를 붙이지
    않았다 — 엔티티가 생기는 시점에 `postgres-inventory`를 공유하도록 연결할 예정.
- 서비스 코드(order/payment/inventory 각 모듈)에 다른 도메인 패키지 import가 없음을
  재확인했다 — 2.1에서 이미 크로스 도메인 결합을 제거해뒀기 때문에 DB 분리 자체는 순수하게
  마이그레이션/설정 변경만으로 끝났다.

**§6 대안 재평가**: §6에서 기각했던 두 대안("서비스별 `flyway.table` 분리", "일부 서비스
Flyway 비활성화")이 지금은 필요 없어졌다 — 애초에 DB가 물리적으로 분리되니 `flyway_schema_history`
자체가 서비스마다 자연스럽게 독립된다. 공유 이력 문제는 "고치는" 게 아니라 전제 자체가
사라지며 해소됐다.

**Testcontainers 테스트 영향 없음**: `PaymentIdempotency*Test`/`InventoryConcurrencyTest`/
`ProductCacheStampedeTest` 등은 이미 각자 독립된 Testcontainers Postgres를 띄워 자기 모듈의
`classpath:db/migration`만 적용받는 구조라(2.1 시점부터) 이번 분리로 테스트 코드 변경은
필요 없었다 — 마이그레이션 파일 내용만 좁아졌다.
