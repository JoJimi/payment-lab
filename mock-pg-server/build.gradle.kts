// 1.5: Spring에 의존하지 않는 순수 JDK HttpServer 기반 Mock PG. 메인 애플리케이션과
// 별도 프로세스로 기동한다 (README/package-info 참고). Boot 플러그인을 적용하지 않는다.
plugins {
    application
}

dependencies {
    // Boot 4 / Jackson 3: databind는 groupId가 tools.jackson.core로 바뀌었다
    // (jackson-annotations만 com.fasterxml.jackson.core에 남아있음). 버전은 루트의
    // spring-boot-dependencies BOM 임포트(io.spring.dependency-management)가 관리한다.
    implementation("tools.jackson.core:jackson-databind")
}

application {
    mainClass.set("org.example.cs_study.mockpg.MockPgServer")
}
