# Spring Boot 4 노트

0단계에서 실제로 겪은 문제와 해결 기록입니다. Boot 4는 자동 설정을 기술별 모듈로 쪼갰기 때문에
**Boot 3 지식을 그대로 쓰면 틀리는 지점**이 많습니다. 새 의존성을 붙일 때마다 여기부터 확인하세요.

환경: Spring Boot 4.1.1 / Spring Framework 7.0.9 / Java 21 / Hibernate 7.4.5

---

## 대원칙

> **라이브러리가 클래스패스에 있다는 것만으로는 자동 설정이 켜지지 않는다.**

Boot 3까지는 단일 `spring-boot-autoconfigure` jar가 모든 기술의 자동 설정을 들고 있었습니다.
Boot 4는 이걸 `spring-boot-<기술>` 모듈로 쪼갰고, 각 모듈에는 대응하는 스타터가 있습니다.

그래서 새 기능을 붙일 때 판단 순서는 이렇습니다.

1. 이 기술에 전용 `spring-boot-*` 스타터/모듈이 있는가? → 있으면 그걸 쓴다
2. 라이브러리만 넣었다면, 기능이 **실제로** 동작하는지 증거로 확인한다

2번이 중요합니다. 이 실패는 대부분 에러를 내지 않습니다.

---

## 실패의 두 종류

| 종류 | 증상 | 발견 난이도 |
|---|---|---|
| **시끄러운 실패** | 기동 자체가 안 됨. 스택트레이스에 원인이 찍힘 | 5분 |
| **조용한 실패** | 빌드 성공, 기동 성공, 해당 기능만 동작 안 함 | 몇 주 |

0단계에서 시끄러운 실패 2건, 조용한 실패 3건을 만났습니다.
**조용한 쪽이 위험합니다.** 아래 검증 체크리스트를 반드시 돌리세요.

---

## 검증 체크리스트

의존성을 추가하거나 변경한 뒤 매번 확인합니다. 기동 성공은 증거가 아닙니다.

```bash
# 좌표가 실제로 해석되는지
./gradlew dependencies --configuration runtimeClasspath
```

| 대상 | 확인 방법 | 통과 기준 |
|---|---|---|
| Flyway | `select * from flyway_schema_history;` | 행이 존재 |
| AOP | 임시 `@Aspect`에 로그를 심고 호출 | 로그가 찍힘 |
| Tracing | 로그를 남기는 엔드포인트 호출 | `[traceId-spanId]` 표시 |

`/actuator/health`는 애플리케이션 로그를 남기지 않으므로 Tracing 확인에 쓸 수 없습니다.
Boot는 HTTP 요청 로그를 기본으로 남기지 않습니다.

> **이 체크리스트는 수동으로 유지될 수 없습니다.** 의존성을 바꿀 때마다 세 가지를 손으로 확인하는 일은
> 두어 번 지나면 건너뛰게 됩니다. R단계에서 이 표의 각 행을 자동 검증 테스트로 만들어
> CI `build-test` 잡에 태웠습니다. 각 문제의 "재발 방지" 항목을 참조하세요.

---

## 스타터 매핑

| Boot 3 | Boot 4 | 실패 유형 |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | 빌드 실패 (4.1에서 별칭 제거) |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` | **조용함** |
| `flyway-core` 단독 | `spring-boot-starter-flyway` | **조용함** |
| `spring-kafka` 단독 | `spring-boot-starter-kafka` | **조용함** |
| OAuth2 스타터 | `security-` 접두사 추가 | 빌드 실패 |

막히면 전환용 `spring-boot-starter-classic` / `spring-boot-starter-test-classic`으로
일단 돌린 뒤 하나씩 풀어내는 방법도 있습니다. 다만 학습 목적상 권하지 않습니다.
어느 스타터가 무엇을 켜는지 아는 것 자체가 Boot 4를 이해하는 과정입니다.

> `spring-kafka` 단독 사용은 아직 겪지 않았지만 같은 함정입니다.
> 2단계에서 Kafka를 붙일 때 `spring-boot-starter-kafka`를 쓰는지 먼저 확인하세요.

---

## 겪은 문제 기록

### 1. Flyway 마이그레이션이 조용히 건너뛰어짐 (조용한 실패)

**증상**
에러 없음. 앱은 정상 기동하고 트래픽도 받지만 마이그레이션만 실행되지 않습니다.

**원인**
`flyway-core`만 넣었습니다. 자동 설정이 라이브러리 존재가 아니라 스타터 안으로 옮겨갔기 때문입니다.

**해결**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-flyway")
runtimeOnly("org.flywaydb:flyway-database-postgresql")
```

**영향 범위**
전 단계. 스키마가 없거나 코드와 어긋난 상태로 진행됩니다.
`ddl-auto: validate`와 조합하면 스키마 검증 실패로 드러나지만,
`update`였다면 Hibernate가 테이블을 만들어버려서 끝까지 몰랐을 겁니다.
이 경우 운영에서는 마이그레이션 이력 없이 스키마가 표류합니다.

**재발 방지**
`FlywayMigrationTest` — Testcontainers로 PostgreSQL을 띄우고 `flyway_schema_history` 행 수가 0보다 큰지 assert.
CI `build-test` 잡에서 실행.

---

### 2. AOP 프록시 미동작 (조용한 실패)

**증상**
에러 없음. `@Aspect` 빈은 등록되는데 어드바이스가 실행되지 않습니다.

**원인**
`spring-boot-starter-aop`이 `spring-boot-starter-aspectj`로 개명됐습니다.
`org.aspectj:aspectjweaver`를 직접 넣으면 AspectJ 클래스는 들어오지만
Boot의 AOP 자동 설정(프록시 활성화)이 빠져서 `@Aspect` 빈이 프록시되지 않습니다.

같은 이유로 Micrometer `@Timed`가 동작을 멈춘 사례가 보고돼 있습니다.

**해결**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-aspectj")
```

**영향 범위**
1단계의 `@Idempotent`, `@DistributedLock`이 전부 여기 올라갑니다.
이게 죽어 있으면 "중복 결제 100건 → 승인 1건" 테스트가 실패했을 때
멱등성 로직 버그인지 AOP 미동작인지 구분할 수 없습니다.
디버깅 시간이 몇 배로 늘어나는 유형의 실패입니다.

**재발 방지**
`AopProxyTest` — 테스트 전용 `@Aspect`와 대상 빈을 띄우고, 대상 메서드 호출 후
인터셉트 카운터가 증가했는지 assert. 어노테이션이 실제로 동작한다는 증거를 남깁니다.

---

### 3. Tracing 미동작 (조용한 실패)

**증상**
에러 없음. 로그에 `[traceId-spanId]`가 그냥 비어 있습니다.

**원인**
`micrometer-tracing-bridge-brave` 하나만으로는 부족합니다.
`BraveAutoConfiguration`은 `spring-boot-micrometer-tracing-brave` 모듈에 있고,
이 모듈은 브릿지를 `optional`로 선언해서 자동으로 딸려오지 않습니다.

**해결**
```kotlin
implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
implementation("io.micrometer:micrometer-tracing-bridge-brave")
```

**둘 중 하나만 있으면 에러 없이 tracing이 꺼진 상태가 됩니다.**

**영향 범위**
4단계의 "주문 하나가 4개 서비스를 지난 로그를 traceId로 조회"가 통째로 불가능해집니다.
더 나쁜 건 시점입니다. 4단계에서야 발견하면 그때까지 쌓인 로그 전부에 traceId가 없어
과거 데이터를 소급해서 살릴 수 없습니다.

**재발 방지**
`TracingEnabledTest` — 테스트 appender로 로그를 캡처해 MDC에 `traceId`가 존재하는지 assert.
0단계에서 켜두는 것이 중요한 이유가 이것입니다.

---

### 4. Spring AI 1.x 기동 실패 (시끄러운 실패)

**증상**
```
ClassNotFoundException:
  org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration
```

**원인**
Spring AI 1.0.0이 Boot 3 시절 FQCN을 애노테이션에 박아둔 채 컴파일돼 있어서,
Spring이 애노테이션을 파싱하는 순간 터집니다.

**설정으로 우회되지 않습니다.** 옛 이름의 클래스는 Boot 4 어디에도 없습니다.
`spring-boot-starter-restclient`를 추가해도, `classic` 스타터를 써도 안 됩니다.

**해결**
버전 라인이 프레임워크에 묶여 있으므로 Spring AI 2.0 이상을 써야 합니다.

| Spring AI | 대상 Boot |
|---|---|
| 1.0.x / 1.1.x | 3.4 / 3.5 |
| 2.0.x | 4.0 / 4.1 |

**영향 범위**
4단계 전체. 다만 Spring AI 2.0은 1.0에서 API가 꽤 바뀌었습니다.
인터넷의 1.0 기준 RAG 예제가 그대로 안 붙으니 4단계에서는 공식 레퍼런스를 먼저 보세요.

**재발 방지**
4단계 착수 시점에 의존성 버전을 먼저 확인. 의존성 추가 직후 `@SpringBootTest`가
컨텍스트를 실제로 띄우는지부터 돌립니다. (아래 "테스트" 절 참조)

---

### 5. EmbeddingModel 빈 없음 (시끄러운 실패)

**증상**
```
Parameter 2 of method vectorStore ... required a bean of type
'org.springframework.ai.embedding.EmbeddingModel' that could not be found.
```

**원인**
**Anthropic API에는 임베딩 엔드포인트가 없습니다.** 채팅 모델만 제공합니다.
RAG는 두 개의 모델이 필요합니다.

| 역할 | 하는 일 |
|---|---|
| Embedding Model | 문서·질의를 벡터로 변환 (Retrieval) |
| Chat Model | 검색 결과로 답변 생성 (Generation) |

**해결**
4단계에서 되살릴 때 임베딩 제공자를 함께 추가합니다.
`spring-ai-starter-model-transformers`가 로컬 ONNX 기반이라 API 키가 추가로 필요 없습니다.

**영향 범위**
4단계의 RAG 전체. VectorStore가 뜨지 않으므로 검색 자체가 불가능합니다.

**재발 방지**
위 4번과 동일. 컨텍스트 로딩 테스트가 먼저 잡습니다.

---

## 테스트

**테스트 슬라이스는 없어지지 않았습니다.** 패키지가 이동했고 모듈별 짝 스타터가 필요합니다.
(초기에 "슬라이스가 제거됐다"고 잘못 판단해 모든 테스트를 순수 JUnit으로 쓰려던 적이 있음)

- `@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`,
  `spring-boot-starter-webmvc-test` 필요
- `@MockBean` / `@SpyBean` 제거 → `@MockitoBean` / `@MockitoSpyBean`.
  `@Configuration` 안에서는 사용 불가 → 테스트 클래스에 `@MockitoBean(types = X.class)`
- `@SpringBootTest`가 MockMvc를 자동 설정하지 않음 → `@AutoConfigureMockMvc` 명시
- `MockitoTestExecutionListener` 제거 → 순수 `@Mock`/`@Captor`는 `MockitoExtension` 직접 등록
- `TestRestTemplate` 미제공 → `RestTestClient` + `@AutoConfigureRestTestClient`

Testcontainers 2.x는 모듈명이 `testcontainers-*` 접두사로 바뀌고 컨테이너 클래스도 이동했습니다.

```
org.testcontainers.containers.PostgreSQLContainer
→ org.testcontainers.postgresql.PostgreSQLContainer
```

> `@SpringBootTest` 없는 `contextLoads()`는 아무것도 검증하지 않습니다.
> 위 4·5번 기동 실패는 둘 다 `./gradlew test`가 먼저 잡았어야 할 문제였습니다.

---

## Jackson 3 (2단계 이벤트 계약에 영향)

- 패키지 `com.fasterxml.jackson` → `tools.jackson` (애노테이션 jar는 좌표/패키지 유지)
- 프로퍼티가 **기본 알파벳 순 정렬**. 순서가 중요하면 `@JsonPropertyOrder` 명시
- `JacksonException`이 `RuntimeException` 상속 → `catch (IOException)`으로 안 잡힘
- `@JsonComponent` → `@JacksonComponent`
- 클래스패스의 **모든** Jackson 모듈이 자동 등록됨 (Boot 3은 알려진 것만)
  → 전이 의존성 하나가 JSON 모양을 바꿀 수 있음

마지막 항목이 2단계에서 문제가 됩니다. 이벤트 봉투(envelope)의 JSON 모양이 바뀌면
이미 발행된 이벤트와 스키마가 어긋납니다. 이벤트 직렬화는 반드시 계약 테스트로 고정하세요.

---

## 기타 확인된 사항

- Actuator health 클래스가 `org.springframework.boot.health.*`로 이동
- K8s liveness/readiness probe가 기본 활성 (`/actuator/health`에 자동 노출)
- Boot 4는 Java 17 이상 (21 권장). "Java 21 필수"는 잘못된 정보
- Undertow 제거됨. Tomcat 11 또는 Jetty 12.1만 가능

---

## 프로젝트 결정 사항

Boot 4 자체와는 별개로, 0단계에서 내린 설정 결정들입니다.

### 로깅: 콘솔은 사람용, 파일은 기계용

`logstash-logback-encoder` + `logback-spring.xml` 조합을 제거하고 Boot 내장 구조화 로깅으로 전환.

```yaml
logging:
  structured:
    format:
      file: ecs
    ecs:
      service:
        name: payment-lab
        environment: dev
  file:
    name: logs/payment-lab.json
```

- 콘솔: Boot 기본 컬러 패턴 (correlation ID 자동 포함)
- 파일: ECS 포맷 JSON → 4단계 Filebeat 경유 적재와 그대로 맞물림
- ECS는 Elasticsearch/Kibana 표준 필드 규격이라 인덱스 템플릿이 바로 맞음
- `service.name`은 2단계에서 서비스 4개를 구분하는 키가 됨

> ⚠️ `logs/payment-lab.json`에는 결제 페이로드가 들어갑니다. `.gitignore`에 `logs/`가
> 포함돼 있는지 반드시 확인하세요.

### correlation ID 읽는 법

```
6aa888950d6dfffc81a958afa816ccc5-81a958afa816ccc5
└──────── traceId (128비트) ────┘ └ spanId (64비트) ┘
```

- traceId = 요청 전체 / spanId = 그 안의 작업 단위
- root span은 spanId가 traceId의 하위 64비트와 동일
- 앞 8자리는 epoch 초 (`6aa88895` = 2026-09-15 08:51:49)
- **MDC는 ThreadLocal**이라 `@Async`·Kafka 컨슈머 스레드에서는 끊김
  → 2단계 "이벤트 공통 봉투 정의"에 `traceId` 필드를 실어 나르는 이유

### Redis 리포지토리 비활성

```yaml
spring.data.redis.repositories.enabled: false
```

멱등성 키는 `RedisTemplate`, 분산락은 Redisson `RLock`으로 다루므로
`@RedisHash` 기반 리포지토리 추상화는 쓰지 않습니다.
`Multiple Spring Data modules found` 경고와 불필요한 스캔이 사라집니다.

### 서킷브레이커 health indicator 비활성

```yaml
resilience4j.circuitbreaker.instances.mockPg.register-health-indicator: false
```

`true`면 서킷 OPEN 시 `/actuator/health`가 DOWN이 됩니다.
3단계에서 PG를 죽여 서킷을 여는 순간 멀쩡한 서비스가 DOWN으로 표시되고,
5단계에서 readiness probe에 물리면 **외부 장애 때 내 파드가 빠집니다.**
3단계 목표인 "장애 전파 차단"과 정반대입니다.
서킷 상태 관찰은 health가 아니라 Micrometer 메트릭 + Grafana로 합니다.

> 이 값이 `true`로 되돌아가는 걸 막기 위해 CodeRabbit `path_instructions`에
> YAML 설정 변경 시 확인하도록 등록했습니다.

### 4단계 의존성 비활성

Elasticsearch와 Spring AI를 주석 처리했습니다. 4단계 전까지 쓰지 않는데
Spring Data 모듈 스캔이 늘어 기동이 느려집니다.

| 구성 | 컨텍스트 초기화 | 전체 기동 |
|---|---|---|
| ES + Spring AI 포함 | 7.4초 | 35.5초 |
| 제외 | 2.8초 | 16.8초 |

> 기동 시간 기준선: 약 17~22초 (로컬, Windows, 코드 변경 후 재시작 시 더 걸림)
> 5단계에서 K8s startup probe 시간을 정할 때 이 값이 기준이 됩니다.
> 컨테이너 안에서 재측정할 것.