// 2.1: @Idempotent 어노테이션 + AOP Aspect. Redis(1차) + Postgres idempotency_keys 테이블(2차)
// 2단 방어(부록 A-1)를 구현한다. 도메인 중립 — 현재는 payment-service만 쓰지만 재사용 가능하게 유지.
dependencies {
    implementation(project(":common-web"))

    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("io.micrometer:micrometer-core")
}
