// 2.16: @KafkaListener 공통 오류 처리(DLQ) 전용 모듈. common-event(Spring 비의존)와 달리
// Spring Kafka 타입(CommonErrorHandler 등)에 직접 의존하므로 별도 모듈로 뺐다 — 소비 서비스는
// 이 모듈만 추가하면 리스너 코드를 한 줄도 안 고치고 재시도+DLQ를 얻는다(자동구성이
// CommonErrorHandler 빈 하나를 기본 리스너 컨테이너 팩토리에 자동으로 물려준다).
dependencies {
    implementation("org.springframework.kafka:spring-kafka")

    // ---- Test ----
    // 도메인 중립 컴포넌트는 이 모듈 스스로 검증한다(common-outbox/common-inbox와 같은 패턴) —
    // 임베디드 브로커로 리스너가 재시도를 소진한 뒤 실제로 <토픽>.DLT에 쌓이는지 증명한다.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
