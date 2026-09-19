package org.example.cs_study;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 전체 컨텍스트 로딩 검증은 ContainerConnectivityTest 에서 수행합니다.
// 이 클래스는 빌드 파이프라인의 smoke test 역할입니다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CsStudyApplicationTests {

    @Test
    void contextLoads() {
    }
}
