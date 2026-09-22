package org.example.cs_study.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// common-web(GlobalExceptionHandler 등)은 org.example.cs_study.common 아래에 있어
// 기본 컴포넌트 스캔 범위(이 클래스의 패키지 하위)에 들지 않는다. 명시적으로 포함시킨다.
@SpringBootApplication(scanBasePackages = {"org.example.cs_study.order", "org.example.cs_study.common"})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
