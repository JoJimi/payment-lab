# CLAUDE.md

결제 도메인으로 백엔드 CS 개념(동시성 제어, 분산 트랜잭션, 이벤트 아키텍처, 성능 최적화)을
단계적으로 구현하는 **학습 프로젝트**입니다. 기능 완성이 아니라 "왜 어려운지를 숫자로 증명"이 목표입니다.

- 현재 단계: **R단계 완료 → 1단계 착수 준비** — CI 게이트(build-test/sast/sca-dependency)
  전부 실전 검증됨. Dependabot PR 7개 + CI 인프라 수정 PR 2개(#8, #9)를 병합하며
  R.CI10을 실전 CVE로 대신 증명함 (docs/stages/01-repository-ci.md 참고)
- 상세 로드맵: @docs/roadmap.md
- 설계 배경 / 기술 선택 이유: @README.md
- **Spring Boot 4 함정과 결정 기록: @docs/troubleshooting/00-spring-boot-4.md**

---

## 작업 원칙

1. **불확실하면 멈추고 물어본다.** 특히 Spring Boot 4 관련 좌표·패키지명은 학습 데이터가
   Boot 3 기준이라 틀리기 쉽습니다. 추측으로 코드를 쓰지 말고 근거를 확인하거나 질문하세요.
2. **요청하지 않은 추상화를 넣지 않는다.** 인터페이스, 제네릭, 전략 패턴은 실제로 2개 이상
   구현이 생길 때 도입합니다. 예외: 1-D 락 4종은 처음부터 전환 가능하게 설계.
3. **한 번에 한 개념만 켠다.** 락 실험 중에는 캐시를 끄고, 캐시 실험 중에는 락 경합을 없앱니다.
4. **현재 작업과 무관한 파일은 건드리지 않는다.**
5. **단계마다 재현 가능한 증거를 남긴다.** 완료 기준은 대부분 측정값이거나 자동화된 테스트입니다.

---

## 명령어

```bash
./gradlew build                    # 빌드
./gradlew test                     # 전체 테스트 (Docker 필요 — Testcontainers)
./gradlew test --tests "org.example.cs_study.ContainerConnectivityTest"
./gradlew compileJava compileTestJava
./gradlew bootRun                  # dev 프로파일

docker compose -f docker-compose.yml up -d    # 0~1단계 (PostgreSQL + Redis)

# 의존성 좌표 검증 (Boot 4 작업 시 필수)
./gradlew dependencies --configuration runtimeClasspath
```

---

## 기술 스택

| 레이어 | 기술 | 버전 |
|---|---|---|
| 언어 / 프레임워크 | Java 21, Spring Boot | 4.1.1 (Framework 7.0.9) |
| 빌드 | Gradle (Kotlin DSL) | 9.7.1 |
| DB / 마이그레이션 | PostgreSQL 16, JPA(Hibernate 7.4), Flyway | Boot BOM |
| 캐시 / 분산락 | Redis 7, Redisson | 4.7.0 |
| 메시징 | Kafka (KRaft 단일 브로커) | Boot BOM |
| 장애 방어 | Resilience4j (`resilience4j-spring-boot4`) | 2.4.0 |
| 모니터링 | Actuator, Micrometer, Prometheus, Grafana | Boot BOM |
| 검색 / RAG | Elasticsearch, Spring AI | **4단계까지 비활성** |
| 테스트 | JUnit 5, Testcontainers | 2.x |

Boot 3과 Boot 4는 사실상 다른 프레임워크입니다.
Redisson 3.x / Spring AI 1.x / `resilience4j-spring-boot3`은 **Boot 4에서 동작하지 않습니다.**

Elasticsearch와 Spring AI 의존성은 `build.gradle.kts`에서 주석 처리돼 있습니다.
4.1 / 4.9 태스크에서 되살리며, 주의사항은 @docs/spring-boot-4-notes.md 에 있습니다.

---

## ⚠️ Spring Boot 4 — 반드시 지킬 것

> **라이브러리가 클래스패스에 있다는 것만으로는 자동 설정이 켜지지 않습니다.**
> 빌드 성공, 기동 성공, 해당 기능만 조용히 죽는 것이 이 버전의 대표 실패 양상입니다.
> 새 의존성을 붙일 때마다 전용 `spring-boot-*` 모듈이 있는지 먼저 확인하세요.

### 스타터 매핑

| Boot 3 | Boot 4 |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` |
| `flyway-core` 단독 | `spring-boot-starter-flyway` **필수** |
| `spring-kafka` 단독 | `spring-boot-starter-kafka` **필수** |
| `micrometer-tracing-bridge-brave` 단독 | `spring-boot-micrometer-tracing-brave` **함께 필요** |

### 의존성 변경 후 검증 (기동 성공은 증거가 아님)

| 대상 | 확인 방법 |
|---|---|
| Flyway | `select * from flyway_schema_history;` 에 행이 있는가 |
| AOP | 임시 `@Aspect`의 로그가 실제로 찍히는가 |
| Tracing | HTTP 요청 로그에 `[traceId-spanId]`가 붙는가 |

`/actuator/health`로는 Tracing을 확인할 수 없습니다. Boot는 HTTP 요청 로그를 기본으로 남기지 않아서,
로그를 직접 남기는 엔드포인트가 따로 필요합니다.

### 테스트

테스트 슬라이스는 **없어지지 않았습니다.** 패키지가 이동했고 모듈별 짝 스타터가 필요합니다.

- `@MockBean` / `@SpyBean` → `@MockitoBean` / `@MockitoSpyBean`
- `@SpringBootTest`가 MockMvc를 자동 설정하지 않음 → `@AutoConfigureMockMvc` 명시
- `@SpringBootTest` 없는 `contextLoads()`는 아무것도 검증하지 않음

전체 목록과 Jackson 3 변경점은 @docs/spring-boot-4-notes.md 참고.

**이 섹션과 실제 동작이 다르면, 코드를 우회하지 말고 이 파일과 노트를 고치세요.**

---

## 패키지 구조

```
org.example.cs_study
├── order/        # 주문 생성, Saga 오케스트레이션
├── payment/      # Mock PG 연동, 결제 처리, 멱등성, 분산락
├── inventory/    # 재고 차감, 동시성 제어
├── notification/ # Kafka 이벤트 컨슈밍, 알림 발송
└── common/       # AOP 어노테이션, 이벤트 계약 DTO, 공통 유틸
```

2단계에서 이 패키지 경계가 그대로 서비스 경계가 됩니다.
**하위 패키지끼리 직접 의존하지 마세요.** 서로를 참조해야 하면 `common`을 경유하거나,
2단계 이후에는 Kafka 이벤트로만 통신합니다.

---

## 코드 규칙

- **금액은 `BigDecimal` + 통화 코드.** DB는 `numeric(19,4)`. `double`/`float` 금지.
- **시각은 UTC 저장(`timestamptz`), 표시할 때만 KST 변환.** `hibernate.jdbc.time_zone: UTC`.
- **스키마는 Flyway가 관리.** `ddl-auto: validate` 고정. 엔티티를 고쳤으면 마이그레이션 파일도 추가.
- **상태 전이는 표를 먼저 문서로 그리고 코드화.** 주문 `CREATED→PAID→FAILED→CANCELLED`,
  결제 `PENDING→APPROVED→FAILED→CANCELLED`.
- **타임아웃된 결제는 `FAILED`가 아니라 `UNKNOWN`.** 실무 사고 1순위 지점입니다.
- **멱등성은 2단 방어.** Redis SETNX(1차) + DB unique 제약(2차). `@Idempotent` AOP로 분리.
  Redis TTL은 짧게(10분), DB 레코드는 길게(24시간).
- **재시도를 붙이기 전에 그 호출이 멱등한지 먼저 확인.**
- 측정값은 `docs/benchmark-*.md`에 표로 남깁니다. 워밍업 후 3회 반복, 중앙값.
  **측정표 상단에 Hikari 풀 크기를 환경 정보로 반드시 명시할 것** (락 비교의 숨은 변수).

---

## 설정

| 파일 | 역할 |
|---|---|
| `application.yml` | 공통 기본값 (기본 프로파일: dev) |
| `application-dev.yml` | 로컬 (PostgreSQL/Redis localhost) |
| `db/migration/V*__*.sql` | Flyway 마이그레이션 |

- 로깅: **콘솔은 컬러 텍스트(사람용), 파일은 ECS JSON(기계용)**.
  `logging.structured.format.file=ecs` + `logging.file.name=logs/payment-lab.json`.
  `logback-spring.xml`은 사용하지 않습니다. `logs/`는 `.gitignore`.
- `management.tracing.sampling.probability: 1.0` — 전 요청 추적.
  correlation ID 형식은 `[traceId-spanId]`.
- 주요 설정에는 **왜 그 값인지** 주석이 달려 있습니다. 값을 바꿀 때 주석도 함께 갱신하세요.

---

## 로컬 리소스 (16GB RAM 제약)

전부 동시에 띄우는 구성은 없습니다. 단계별 compose 조합으로 켜고 끕니다.

| 단계 | 명령 |
|---|---|
| 0–1 | `docker compose -f docker-compose.yml up -d` |
| 2 | `+ docker-compose.kafka.yml` |
| 3 | `+ docker-compose.observability.yml` |
| 4 | `+ docker-compose.elk.yml` (Prometheus/Grafana는 내림) |

새 미들웨어를 추가할 때는 힙 상한을 반드시 지정하세요 (ES `-Xms1g -Xmx1g`, Kafka 512MB).
Testcontainers 이미지 태그는 compose 파일과 동일하게 고정합니다 (`postgres:16-alpine`).