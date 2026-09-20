plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "0.0.1-SNAPSHOT"
description = "cs_study"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// R.CI4: Trivy는 Gradle 의존성을 gradle.lockfile로만 인식함.
// 락파일이 없으면 Java 의존성 취약점이 0건으로 "조용히" 통과하므로 CI 게이트의 전제 조건.
// `./gradlew dependencies --write-locks` 로 생성 후 커밋.
dependencyLocking {
    lockAllConfigurations()
}

extra["springAiVersion"] = "2.0.0"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }

    // Boot 4.1.1 BOM이 관리하는 전이 의존성 중 Trivy(sca-dependency)가 탐지한 CVE 수정 버전으로 강제 상향.
    // Tomcat: FORM 인증 우회 / DIGEST 재전송 공격 / 접근 제어 우회 (11.0.24 → 11.0.26)
    // lz4-java: XXHash JNI 검증 미흡으로 인한 DoS (1.10.1 → 1.11.3)
    dependencies {
        dependency("org.apache.tomcat.embed:tomcat-embed-core:11.0.26")
        dependency("org.apache.tomcat.embed:tomcat-embed-el:11.0.26")
        dependency("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.26")
        dependency("at.yawk.lz4:lz4-java:1.11.3")
    }
}

dependencies {
    // Spring AI 2.0 = Boot 4.0/4.1 + Framework 7 라인. 1.x는 Boot 3.5 전용이라 사용 불가.
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))

    // Testcontainers 2.x: 모듈명이 testcontainers-* 접두사로 변경됨
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))

    // ---- Web / 기본 ----
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // ---- DB / 마이그레이션 ----
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // ---- Redis 캐싱 / 분산락 ----
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.redisson:redisson-spring-boot-starter:4.7.0")
    implementation("org.redisson:redisson-spring-cache:4.7.0")

    // ---- Kafka 이벤트 ----
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    // ---- 외부 API 방어 (Circuit Breaker / Retry / TimeLimiter) ----
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    // ---- 모니터링 / 성능 측정 ----
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ── Tracing: 두 줄이 모두 필요함 ──
    // BraveAutoConfiguration 은 spring-boot-micrometer-tracing-brave 모듈 안에 있고,
    // 이 모듈은 브릿지를 optional 로 선언해서 자동으로 딸려오지 않음.
    // 반대로 브릿지만 넣으면 자동설정이 없어 traceId 가 MDC에 들어가지 않음.
    // → 둘 중 하나만 있으면 "에러 없이" tracing 이 꺼진 상태가 됨.
    //
    // 확인법: 로그를 남기는 엔드포인트를 호출해
    //   [payment-lab] [<traceId>-<spanId>] [nio-8080-exec-1] ...
    //   형태로 correlation ID 가 찍히는지 볼 것.
    //   (/actuator/health 는 앱 로그를 남기지 않아 확인 불가)
    // 이게 안 되면 4.4("주문 하나가 4개 서비스를 지난 로그를 traceId로 조회")가 불가능.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    // ---- 구조화 로깅 (JSON + traceId MDC) ----
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // ---- Elasticsearch (검색 / RAG 벡터스토어) ----
    // implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // ---- Agent / RAG (Spring AI) ----
    // implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    // implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    // implementation("org.springframework.ai:spring-ai-starter-vector-store-elasticsearch")
    // TODO(4.9): 되살릴 때 spring-ai-starter-model-transformers 도 함께 추가할 것.
    //            Anthropic은 임베딩 모델을 제공하지 않아 vector store 가 기동 실패함.

    // ---- Lombok ----
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ---- Test ----
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Redis는 Testcontainers 공식 전용 모듈이 없음(Postgres/Kafka와 다름).
    // org.testcontainers:testcontainers-redis 좌표는 Maven Central에 존재하지 않음 — 순수 GenericContainer로 사용.
    testImplementation("org.testcontainers:testcontainers-kafka")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 기본 콘솔 출력은 예외 타입+위치만 보여주고 메시지/전체 스택트레이스를 생략한다.
    // CI 로그만으로 원인을 진단할 수 있어야 하므로(로컬 재현이 항상 가능한 건 아님) 켜둔다.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        events("failed")
    }
}

// @Idempotent(key = "#idempotencyKey") 같은 SpEL이 파라미터 이름을 리플렉션으로 읽어야 하므로 필요.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

// 1.5: Mock PG를 별도 프로세스로 기동. 메인 앱(bootRun)과 동시에 띄워야 한다.
// 포트를 바꾸려면: ./gradlew mockPgRun --args="8091"
tasks.register<JavaExec>("mockPgRun") {
    group = "application"
    description = "Mock PG 서버를 별도 프로세스로 기동한다 (기본 포트 8090)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.cs_study.mockpg.MockPgServer")
}
