// 2.12: 이 서비스의 첫 실제 도메인 로직(알림 기록) + 첫 영속 대상(Notification 엔티티).
// 2.2 결정대로 inventory-service와 DB를 공유한다(application-dev.yml).
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-web"))
    implementation(project(":common-event"))
    implementation(project(":common-inbox"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // ---- DB / 마이그레이션 (2.12에서 처음 필요해짐) ----
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // ---- Kafka (2.12: @KafkaListener) ----
    implementation("org.springframework.kafka:spring-kafka")

    // ---- Test ----
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
