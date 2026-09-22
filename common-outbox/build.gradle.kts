// 2.8: Transactional Outbox 공통 구현 (로드맵 부록 A-3). order/payment/inventory가 각자 자신의
// DB에 outbox 테이블을 갖고 있다(2.2에서 스켈레톤만 만들어둠) — 이 모듈이 "그 테이블에 쓰고,
// 폴링해서 Kafka로 발행하는" 로직을 한 번만 구현해 세 서비스가 공유한다. common-idempotency와
// 같은 패턴(도메인 중립 JPA 컴포넌트를 common 모듈로)을 따른다.
dependencies {
    implementation(project(":common-event"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-json")

    // ---- Test ----
    // OutboxService(DB만 관여)는 Testcontainers Postgres로, OutboxRelay(Kafka 발행까지 관여)는
    // 추가로 spring-kafka-test의 임베디드 브로커로 검증한다 — 둘 다 이 모듈 자신의 계약이라
    // (common-idempotency가 그랬듯) 소비 서비스에 얹혀서가 아니라 여기서 직접 증명한다.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("org.postgresql:postgresql")
}
