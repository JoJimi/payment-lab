// 2.9: 컨슈머 멱등성 공통 구현. Kafka는 at-least-once라 같은 이벤트가 두 번 올 수 있다(2-B 개요) —
// order/payment/inventory가 각자 자신의 DB에 processed_event 테이블을 두고, 이 모듈의
// InboxService로 "처리 전 존재 확인 → 처리 → 기록"을 한 트랜잭션에 묶는다.
// common-outbox와 대칭을 이루는 이름(Outbox=발행/Inbox=소비)이다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // ---- Test ----
    // common-idempotency/common-outbox와 같은 패턴 — 도메인 중립 컴포넌트는 이 모듈 스스로
    // Testcontainers Postgres로 검증한다.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
