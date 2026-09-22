// 2.1: @Idempotent 어노테이션 + AOP Aspect. Redis(1차) + Postgres idempotency_keys 테이블(2차)
// 2단 방어(부록 A-1)를 구현한다. 도메인 중립 — 현재는 payment-service만 쓰지만 재사용 가능하게 유지.
dependencies {
    implementation(project(":common-web"))

    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("io.micrometer:micrometer-core")

    // ---- Test ----
    // 2.1: 멱등성 메커니즘 자체(동시 요청 dedup, Redis 다운 내성)를 이 모듈에서 직접 검증한다.
    // 이전엔 payment-service의 결제 흐름에 얹혀서만 검증됐는데(1.9/1.10), payment-service가
    // 2-B 전까지 결제를 명시적으로 거부하게 되면서 더 이상 그 경로로는 증명이 안 된다 — 원래
    // "도메인 중립"이라고 스스로 문서화한 컴포넌트이니 소유 모듈 안에서 직접 테스트하는 게 맞다.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator") // IdempotencyAspect가 주입받는
    // MeterRegistry 빈은 실제 소비 서비스(payment-service)가 물고 있는 actuator 스타터가 자동 구성해준다 —
    // 이 모듈 자체 테스트도 같은 방식으로 빈을 공급받아야 한다.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    // Flyway 없이 ddl-auto=create-drop으로 idempotency_keys 테이블을 만든다 — 이 모듈의 테스트는
    // 마이그레이션 자체가 아니라 AOP/Redis/DB dedup 동작을 검증하는 게 목적이라 굳이 안 끌어온다.
}
