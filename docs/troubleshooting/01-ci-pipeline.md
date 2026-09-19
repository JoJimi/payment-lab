# CI 파이프라인 트러블슈팅

환경: Gradle 9.7.1 / Testcontainers 2.0.5 / Spring Boot 4.1.1

## 대원칙
Testcontainers 2.x는 Postgres/Kafka처럼 공식 모듈이 있는 것과, Redis처럼 공식 모듈이
아예 없는 것을 구분해야 한다. "1.x에 있었으니 2.x에도 이름만 바뀌어 있겠지"라고 추측하면
존재하지 않는 좌표를 그대로 쓰게 된다.

## 검증 체크리스트
| 대상 | 확인 방법 | 통과 기준 |
|---|---|---|
| 의존성 좌표 | `./gradlew compileTestJava` | 좌표 해석 실패(Could not find ...) 없음 |
| gradle.lockfile | `test -f gradle.lockfile` | 존재하고 최신 (`--write-locks` 재실행 후 diff 없음) |
| required status check | Ruleset 설정 화면 | `build-test`/`sast`/`sca-dependency` 3개 모두 "Expected" 아닌 실제 실행 |

## 겪은 문제

### 1. `org.testcontainers:testcontainers-redis` 좌표가 존재하지 않음 (시끄러운 실패)

**증상**
```
Could not resolve all files for configuration ':testCompileClasspath'.
> Could not find org.testcontainers:testcontainers-redis:.
```
`./gradlew build`는 물론 `compileTestJava`부터 실패해서 R단계 CI를 설계하기도 전에
로컬 빌드 자체가 깨져 있었다.

**원인**
Testcontainers는 Postgres/Kafka/MongoDB처럼 프로토콜을 아는 전용 모듈을 제공하지만,
**Redis 전용 공식 모듈은 애초에 없다.** `redis:7-alpine` 같은 일반 이미지는
`GenericContainer`로 다루는 것이 Testcontainers 진영의 정석이다. Maven Central에
`org.testcontainers:testcontainers-redis`는 어떤 버전으로도 존재하지 않는다
(`repo1.maven.org/.../testcontainers-redis/maven-metadata.xml` → 404).

**해결**
`build.gradle.kts`에서 해당 좌표를 제거하고, 기존 `ContainerConnectivityTest`가 이미
쓰고 있던 방식 그대로 `GenericContainer<>("redis:7-alpine").withExposedPorts(6379)`를
그대로 유지했다.

**영향 범위**
전 단계. `testCompileClasspath` 해석이 실패하면 테스트뿐 아니라 `./gradlew build` 자체가
안 되므로 R단계 CI의 `build-test` 잡이 무조건 빨간불로 시작한다.

**재발 방지**
좌표를 추가할 때 "공식 프로토콜 모듈이 실제로 존재하는가"를 Maven Central에서 먼저
확인한다(CLAUDE.md 작업 원칙 1). 존재 자체를 추측하지 않는다.

---

### 2. Testcontainers 2.x `PostgreSQLContainer`가 더 이상 제네릭이 아님 (시끄러운 실패)

**증상**
```
error: type PostgreSQLContainer does not take parameters
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(...)
```

**원인**
구 패키지 `org.testcontainers.containers.PostgreSQLContainer<SELF>`는 유지되지만
deprecated 처리됐고, 신 패키지 `org.testcontainers.postgresql.PostgreSQLContainer`는
제네릭 파라미터가 없는 단순 클래스로 바뀌었다(`docs/troubleshooting/00-spring-boot-4.md`의
"테스트" 절에서 이미 이 이동을 언급했지만, 시그니처가 non-generic으로 바뀐 것까지는
문서화돼 있지 않았다).

**해결**
```java
import org.testcontainers.postgresql.PostgreSQLContainer;
// ...
static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
```

**영향 범위**
Testcontainers를 쓰는 모든 테스트. 구 패키지도 당장은 컴파일되므로(deprecated 경고만
발생) 조용히 넘어가기 쉽지만, 메이저 업그레이드에서 제거될 수 있는 API에 의존하게 된다.

**재발 방지**
`ContainerConnectivityTest`, `QuietFailureRegressionTest` 둘 다 신 패키지로 통일.
새 테스트를 추가할 때 `org.testcontainers.containers.*` import가 보이면 구 패키지임을
의심한다.

---

### 3. Boot 4의 `RestTestClient`/`@AutoConfigureRestTestClient` 패키지 위치 (조용한 실패 아님, 추측 방지 사례)

**증상**
해당 아님 — 코드를 쓰기 전에 확인한 사례.

**원인**
`docs/troubleshooting/00-spring-boot-4.md`는 "`TestRestTemplate` 미제공 →
`RestTestClient` + `@AutoConfigureRestTestClient`"까지만 기록돼 있고 정확한 FQCN은
없었다. Boot 3 지식으로 패키지를 추측하면 틀리기 쉬운 지점(CLAUDE.md 작업 원칙 1)이라
클래스패스에서 직접 확인했다.

**해결**
Gradle 캐시의 jar를 직접 열어 확인:
- `org.springframework.test.web.servlet.client.RestTestClient` (spring-test 7.0.9)
- `org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient`
  (`spring-boot-resttestclient` 모듈 — `spring-boot-starter-webmvc-test`가 전이 의존성으로
  끌고 옴. 별도 추가 불필요)

**영향 범위**
R.CI6의 Tracing 검증 테스트(`QuietFailureRegressionTest`)가 HTTP 요청을 실제로 보내야
MDC traceId를 확인할 수 있어 이 클라이언트가 필수였음.

**재발 방지**
불확실한 Boot 4 FQCN은 코드를 쓰기 전에 `Gradle 캐시 jar 안 클래스 목록 조회` 또는
공식 문서로 확인. 이번에 확인한 값은 이 문서에 남겨 다음에는 바로 재사용 가능.

---

### 4. `dependencyLocking` 없이는 Trivy SCA가 "0건 통과"로 조용히 무력화됨 (조용한 실패, 미연에 방지)

**증상**
해당 아님 — R.CI4를 설계하며 로드맵 경고를 먼저 반영한 사례.

**원인**
Trivy `fs` 스캐너는 Gradle 프로젝트의 Java 의존성을 `gradle.lockfile` 존재 여부로만
인식한다. 락파일이 없으면 스캔 자체가 "취약점 0건"으로 성공 리포트를 내서, CI 로그만
보면 정상으로 보이지만 실제로는 아무것도 검사되지 않은 상태다.

**해결**
```kotlin
dependencyLocking { lockAllConfigurations() }
```
추가 후 `./gradlew dependencies --write-locks` 실행, `gradle.lockfile`을 커밋.
`pr-check.yml`의 `sca-dependency` 잡에도 락파일 존재를 먼저 검증하는 단계를 넣어
락파일이 실수로 삭제/미반영된 PR을 즉시 실패시키게 했다.

**영향 범위**
전 단계. 이 문제는 "게이트가 있다"는 착각을 주면서 실제로는 아무 것도 막지 않는,
가장 나쁜 유형의 조용한 실패다.

**재발 방지**
`sca-dependency` 잡의 `Verify lockfile exists` 스텝 + PR 템플릿 체크리스트에
"의존성을 바꿨다면 gradle.lockfile 갱신" 항목 추가.
