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
    // common-outbox가 이미 이 좌표를 갖고 있지만 Gradle의 implementation 가시성 규칙상
    // 전이되지 않는다 — @KafkaListener/EventType을 이 모듈 코드에서 직접 쓰려면 직접 선언해야 한다.
    implementation("org.springframework.kafka:spring-kafka")

    // ---- DB / 마이그레이션 ----
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // ---- 모니터링 / 성능 측정 ----
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")

    // ---- 구조화 로깅 (JSON + traceId MDC) ----
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // ---- Test ----
    // ContainerConnectivityTest(0단계 완료 기준 검증)가 Postgres/Redis 둘 다 확인한다.
    // order-service 자체는 Redis를 쓰지 않지만, 이 인프라 검증 테스트의 대표 위치로 유지한다.
    testImplementation("io.lettuce:lettuce-core")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // 2.12: @KafkaListener 통합 테스트(EmbeddedKafka, common-outbox OutboxRelayTest와 같은 패턴).
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // 2.19: Saga 정본 회귀 스위트(SagaEndToEndIntegrationTest)만 EmbeddedKafka 대신 진짜
    // Testcontainers Kafka를 쓴다 — 실제 브로커 동작에 더 가까운 검증이 필요해서다.
    testImplementation("org.testcontainers:testcontainers-kafka")
}
