package org.example.cs_study.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// 2.12: common-web(GlobalExceptionHandler)/common-inbox(ProcessedEvent)는
// org.example.cs_study.common 아래에 있어 기본 스캔 범위(이 클래스 패키지 하위)에 들지 않는다
// — order/inventory-service와 같은 이유로 명시적으로 넓힌다(OrderServiceApplication 주석 참고).
@SpringBootApplication(scanBasePackages = {"org.example.cs_study.notification", "org.example.cs_study.common"})
@EnableJpaRepositories(basePackages = {"org.example.cs_study.notification", "org.example.cs_study.common"})
@EntityScan(basePackages = {"org.example.cs_study.notification", "org.example.cs_study.common"})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
