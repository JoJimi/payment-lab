# 2.1 — Gradle 멀티모듈 전환 트러블슈팅 / 설계 결정

모놀리식 단일 모듈(`cs_study`)을 8개 Gradle 모듈(`common-event`, `common-web`,
`common-idempotency`, `mock-pg-server`, `order-service`, `payment-service`,
`inventory-service`, `notification-service`)로 분리한 작업(이슈 #42)에서 내린 판단과,
아직 해소되지 않은 상태를 기록합니다.

환경: Spring Boot 4.1.1 / Java 21 / Gradle 9.7.1 — 이 세션은 Docker가 없는 원격 컨테이너라
컴파일/패키징(`compileJava`/`compileTestJava`/`assemble`)과 Docker 불필요 테스트
(`MockPgServerTest`/`OrderStatusTest`/`PaymentStatusTest`)까지만 실측 검증했다. Testcontainers
기반 통합 테스트와 실제 기동은 로컬에서 확인이 필요하다.

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
