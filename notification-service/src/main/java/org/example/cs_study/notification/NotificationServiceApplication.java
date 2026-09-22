package org.example.cs_study.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 2단계 스텁 — 아직 도메인 로직 없음 (Kafka 컨슈머는 2-B/2-C에서 추가).
@SpringBootApplication(scanBasePackages = {"org.example.cs_study.notification", "org.example.cs_study.common"})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
