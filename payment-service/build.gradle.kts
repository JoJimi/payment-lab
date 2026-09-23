plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-web"))
    implementation(project(":common-event"))
    implementation(project(":common-idempotency"))
    implementation(project(":common-outbox"))
    implementation(project(":common-inbox"))
    implementation(project(":common-kafka"))

    // ---- Web / 기본 ----
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // ---- Kafka (2.12: @KafkaListener) ----
    implementation("org.springframework.kafka:spring-kafka")

    // ---- DB / 마이그레이션 ----
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // ---- 외부 API(Mock PG) 방어 (Circuit Breaker / Retry / TimeLimiter, 3단계용) ----
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    // ---- 모니터링 / 성능 측정 ----
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    // ---- 구조화 로깅 (JSON + traceId MDC) ----
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // ---- Test ----
    // PaymentIdempotency*Test가 Mock PG를 인프로세스로 직접 띄운다(Docker 불필요).
    // 프로덕션 코드는 여전히 HTTP로만 연결한다(client/MockPgClient) — 순수 테스트 픽스처 의존.
    testImplementation(project(":mock-pg-server"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // 2.12: @KafkaListener 통합 테스트(EmbeddedKafka).
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
