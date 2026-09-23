// 2.12: 이 서비스의 첫 실제 도메인 로직(알림 기록) + 첫 영속 대상(Notification 엔티티).
// 2.2 결정대로 inventory-service와 DB를 공유한다(application-dev.yml).
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-web"))
    implementation(project(":common-event"))
    implementation(project(":common-inbox"))
    implementation(project(":common-kafka"))

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
    // spring-kafka 자체는 Boot 비의존 라이브러리라 이것만으로는 컨슈머 팩토리/리스너 컨테이너
    // 자동구성이 안 생긴다(common-outbox의 같은 코멘트 참고) — 이 서비스는 common-outbox를
    // 참조하지 않아(순수 컨슈머, Outbox 발행 없음) 다른 서비스들처럼 전이 의존성으로 딸려오지
    // 않는다. 없으면 @KafkaListener가 조용히 아무 컨테이너에도 안 붙어 메시지를 영원히 못
    // 받는다(CI에서 실측: 코드는 컴파일되고 컨텍스트도 뜨지만 리스너가 죽어 있었다).
    implementation("org.springframework.boot:spring-boot-kafka")

    // ---- Test ----
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
