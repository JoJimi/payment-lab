plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// 2.1: 모놀리식 단일 모듈에서 멀티모듈로 전환. 서비스별 모듈(order/payment/inventory/
// notification-service)은 각자 org.springframework.boot 플러그인을 적용해 독립 실행
// 가능한 bootJar를 만든다. common-* 모듈은 라이브러리 jar만 만든다.
// (docs/roadmap.md 2단계 2-A, 2.1)
subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "org.example"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    // R.CI4: Trivy는 Gradle 의존성을 gradle.lockfile로만 인식함(모듈별로 하나씩 생김).
    // 락파일이 없으면 Java 의존성 취약점이 0건으로 "조용히" 통과하므로 CI 게이트의 전제 조건.
    // `./gradlew dependencies --write-locks` 로 생성 후 커밋.
    dependencyLocking {
        lockAllConfigurations()
    }

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            // Boot 플러그인을 적용하지 않는 순수 라이브러리 모듈(common-*)도 같은 버전 관리를
            // 받도록 Boot BOM을 명시적으로 가져온다. Boot 플러그인을 적용하는 서비스 모듈에서는
            // 플러그인이 이미 이 BOM을 자동 임포트하므로 중복 임포트지만 무해하다.
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1")
        }

        // Boot 4.1.1 BOM이 관리하는 전이 의존성 중 Trivy(sca-dependency)가 탐지한 CVE 수정 버전으로 강제 상향.
        // Tomcat: FORM 인증 우회 / DIGEST 재전송 공격 / 접근 제어 우회 (11.0.24 → 11.0.26)
        // lz4-java: XXHash JNI 검증 미흡으로 인한 DoS (1.10.1 → 1.11.3)
        dependencies {
            dependency("org.apache.tomcat.embed:tomcat-embed-core:11.0.26")
            dependency("org.apache.tomcat.embed:tomcat-embed-el:11.0.26")
            dependency("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.26")
            dependency("at.yawk.lz4:lz4-java:1.11.3")
        }
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")

        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // 기본 콘솔 출력은 예외 타입+위치만 보여주고 메시지/전체 스택트레이스를 생략한다.
        // CI 로그만으로 원인을 진단할 수 있어야 하므로(로컬 재현이 항상 가능한 건 아님) 켜둔다.
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
            showCauses = true
            events("failed")
        }
    }

    // @Idempotent(key = "#idempotencyKey") 같은 SpEL이 파라미터 이름을 리플렉션으로 읽어야 하므로 필요.
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }
}
