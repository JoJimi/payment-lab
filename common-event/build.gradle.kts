// 2.1: 이벤트 계약(DTO) 전용 모듈. 실제 이벤트 목록/필드는 2.6~2.7에서 채운다.
// Spring 비의존 — 어떤 서비스 모듈에서도(심지어 Boot 없이도) 참조할 수 있게 순수 Java로 유지한다.
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations")
}
