// 2.1: 이벤트 계약(DTO) 전용 모듈. 실제 이벤트 목록/필드는 2.6~2.7에서 채운다.
// Spring 비의존 — 어떤 서비스 모듈에서도(심지어 Boot 없이도) 참조할 수 있게 순수 Java로 유지한다.
// slf4j-api는 Spring이 아니라 로깅 퍼사드 자체라 이 원칙을 깨지 않는다(2.7 — traceId MDC 연동).
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    // Jackson 3(tools.jackson)는 Spring이 아니라 순수 직렬화 라이브러리라 "Spring 비의존"
    // 원칙을 깨지 않는다 — common-outbox(OutboxService)가 발행 시점 직렬화에 쓰는 것과 같은
    // 이유로, 여기서는 컨슈머 쪽 역직렬화(EventEnvelopeReader, 2.12)에 쓴다.
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    // slf4j-api 단독으로는 MDC 바인딩이 없어 MDC.put/get이 조용히 no-op된다(NOPMDCAdapter).
    // 실제 서비스에서는 logback-classic이 Boot 스타터로 딸려오지만, 이 모듈은 Boot 비의존이라
    // 테스트에서 MDC 동작을 검증하려면 여기서 직접 붙여야 한다.
    testImplementation("ch.qos.logback:logback-classic")
}
