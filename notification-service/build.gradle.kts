// 2단계 스텁. 실제 알림 로직은 2-B/2-C(Kafka 컨슈머)에서 채워진다.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-web"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
