plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-web"))
    implementation(project(":common-event"))
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

    // ---- Redis 캐싱 / 분산락 ----
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.redisson:redisson-spring-boot-starter:4.7.0")
    implementation("org.redisson:redisson-spring-cache:4.7.0")

    // ---- 모니터링 / 성능 측정 ----
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    // ---- 구조화 로깅 (JSON + traceId MDC) ----
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // ---- Test ----
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
