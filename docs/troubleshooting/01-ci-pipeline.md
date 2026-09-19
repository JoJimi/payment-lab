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
| 로컬 테스트가 CI와 같은 조건인가 | `docker compose -f docker-compose.yml stop` 후 `./gradlew build` | 로컬 미들웨어 없이도 통과 (Testcontainers만으로) |
| 실행 권한 | `git ls-files -s gradlew` | `100755` (실행 비트 포함), `100644`면 CI에서 exit 126 |

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

---

### 5. `gradlew` 실행 권한 누락 → CI에서만 `exit code 126` (조용한 실패, CI 전용)

**증상**
로컬(Windows)에서는 `./gradlew`가 항상 실행됐다. 그런데 `main`에 첫 커밋을 푸시하고
연 PR들의 `build-test` 잡이 전부 `Process completed with exit code 126`으로 실패했다.
스택트레이스도, 컴파일 에러도 없이 딱 한 줄만 남는다.

**원인**
`git ls-files -s gradlew`로 확인하니 `100644`(일반 파일)로 커밋돼 있었다. Windows에서
커밋하면 실행 비트가 애초에 붙지 않는다. Windows 로컬에서는 `.bat`이 아니라 `./gradlew`를
호출해도 Git Bash/WSL이 셸 스크립트를 알아서 실행해 주거나, IDE가 감싸서 실행하기 때문에
증상이 로컬에서는 드러나지 않는다. `exit code 126`은 "명령을 찾았지만 실행 권한이 없다"는
POSIX 셸의 표준 의미로, Linux 러너(ubuntu-latest)에서만 나타난다.

**해결**
```bash
git update-index --chmod=+x gradlew
```
커밋 후 `git ls-files -s gradlew`가 `100755`인지 확인.

**영향 범위**
`build-test` 잡 전체. 이 잡이 죽으면 R.CI6의 조용한 실패 검증 테스트(Flyway/AOP/Tracing)를
포함한 나머지 모든 테스트가 아예 실행조차 되지 않는다. Semgrep/Trivy가 통과해도 실질적으로
아무것도 검증되지 않은 것과 같다.

**재발 방지**
위 검증 체크리스트에 `git ls-files -s gradlew` 확인 항목 추가. Gradle Wrapper를 새로
생성하거나 Windows에서 직접 커밋할 때마다 확인할 것.

---

### 6. 로컬 docker-compose가 Testcontainers 누락을 가려서 CI에서만 터짐 (조용한 실패, 가장 위험했던 사례)

**증상**
로컬에서 `./gradlew test`, `./gradlew build`가 전부 통과했다(이 문서 앞부분의 검증 기록도
포함). 그런데 Dependabot이 처음으로 CI를 실전 가동시키자 `build-test`에서 6개 테스트 중
4개가 `ConnectException`으로 실패했다.

**원인**
`CsStudyApplicationTests`(`@SpringBootTest`, contextLoads 스모크 테스트)와
`QuietFailureRegressionTest`(R.CI6 조용한 실패 검증)가 각각 다음 구멍을 갖고 있었다.

- `CsStudyApplicationTests`: Testcontainers를 전혀 쓰지 않고, dev 프로파일 기본값
  (`localhost:5432`, `localhost:6379`)에 그대로 연결을 시도
- `QuietFailureRegressionTest`: Postgres는 `@Container`로 잘 오버라이드했지만 **Redis는
  빠뜨림** — Redisson은 Lettuce와 달리 기동 시점에 즉시 연결을 맺으므로, Redis가 없으면
  컨텍스트 로딩 자체가 깨지면서 Flyway/AOP/Tracing 테스트 3개가 전부 도미노로 실패

로컬 개발 환경은 `docker compose -f docker-compose.yml up -d`로 Postgres/Redis가 상시
떠 있었기 때문에, 이 테스트들이 "Testcontainers로 격리됐다"는 착각 속에서 실은 로컬 인프라에
암묵적으로 의존한 채 몇 번이나 "통과"를 보고했다. `./gradlew build`가 성공했다는 사실 자체가
검증이 아니었다 — **무엇에 연결해서 통과했는지**를 확인하지 않으면 조용한 실패를 놓친다.

**해결**
```java
@Container
@SuppressWarnings("resource")
static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

@DynamicPropertySource
static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
}
```
두 테스트 클래스 모두 `ContainerConnectivityTest`가 쓰던 패턴을 그대로 재사용해 Postgres +
Redis를 Testcontainers로 격리했다.

**영향 범위**
R.CI6 전체. "조용한 실패를 자동으로 잡아낸다"는 테스트 자체가 조용한 실패를 갖고 있었다는
점에서, 이 프로젝트의 핵심 주제("왜 어려운지를 숫자로 증명한다")와 정확히 같은 패턴이
CI 파이프라인 구축 과정에서 그대로 재현된 사례다.

**재발 방지**
검증 체크리스트에 "로컬 미들웨어를 내리고 다시 테스트" 항목 추가. `@SpringBootTest`를 쓰는
테스트 클래스를 새로 만들 때마다 Postgres/Redis 둘 다 Testcontainers로 격리했는지 확인한다.
CI에서 한 번이라도 그린이 뜬 뒤에야 "로컬에서 됐다"는 근거를 신뢰할 수 있다.

---

### 7. Boot 4.1.1 BOM이 관리하는 전이 의존성의 실제 CRITICAL CVE (조용한 실패 아님 — 게이트가 의도대로 작동한 사례)

**증상**
`sca-dependency`(Trivy fs scan) 잡이 PR #1~#8 전부에서 실패했다. 각 PR이 건드린 내용과
무관한 잡이 똑같이 실패한다는 점에서, 원인이 PR 각각이 아니라 `main`에 이미 있는 무언가라고
추정할 수 있었다.

**원인**
Spring Boot 4.1.1의 dependency-management BOM이 고정한 버전 중 실제 취약점이 있는 것이
있었다.

| 패키지 | 설치된 버전 | 수정 버전 | 심각도 | 내용 |
|---|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-*` | 11.0.24 | 11.0.26 | CRITICAL ×3 | FORM 인증 우회, DIGEST 재전송 공격, 접근 제어 우회 |
| `at.yawk.lz4:lz4-java` | 1.10.1 | 1.11.3 | MEDIUM | XXHash JNI 검증 미흡으로 인한 DoS |

Boot의 패치 릴리즈가 아직 이 버전들을 따라잡지 못한 상태였다.

**해결**
```kotlin
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }

    dependencies {
        dependency("org.apache.tomcat.embed:tomcat-embed-core:11.0.26")
        dependency("org.apache.tomcat.embed:tomcat-embed-el:11.0.26")
        dependency("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.26")
        dependency("at.yawk.lz4:lz4-java:1.11.3")
    }
}
```
`io.spring.dependency-management` 플러그인의 `dependencies { dependency(...) }` 블록으로
BOM이 정한 버전을 개별 강제 상향한 뒤 `./gradlew dependencies --update-locks
"org.apache.tomcat.embed:*","at.yawk.lz4:lz4-java"`로 lockfile을 갱신했다.

**영향 범위**
PR #1~#8 전체의 머지가 막혀 있었다. 다만 이건 "조용한 실패"가 아니라 **게이트가 정확히
설계된 대로 동작한 사례**다 — R.CI10이 의도했던 "취약 코드를 일부러 넣어 막히는지 확인"을,
실제 취약점이 우연히 대신 증명해준 셈이 됐다.

**재발 방지**
Boot 패치 릴리즈(4.1.2+)가 나와 이 버전들을 따라잡으면 위 `dependencies` 블록을 제거할 것.
그 전까지는 Dependabot이 `org.apache.tomcat.embed`/`at.yawk.lz4`를 직접 추적하지 않으므로
(Boot BOM 경유 전이 의존성이라 Dependabot 대상이 아님), Trivy가 유일한 감시 수단이다.
