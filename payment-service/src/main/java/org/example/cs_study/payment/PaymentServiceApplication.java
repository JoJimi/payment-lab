package org.example.cs_study.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan; // Boot 4: 패키지가 여기로 이동함
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// common-web/common-idempotency는 org.example.cs_study.common 아래에 있어
// 기본 컴포넌트 스캔 범위(이 클래스의 패키지 하위)에 들지 않는다. scanBasePackages로 명시적으로
// 포함시킨다 — 단, 이건 @ComponentScan에만 적용되고 @EnableJpaRepositories/@EntityScan의
// 기본 스캔 범위(메인 클래스 패키지, AutoConfigurationPackages 기준)는 별개라 함께 넓혀야 한다.
// 안 그러면 common-idempotency의 IdempotencyRecord/IdempotencyRecordRepository가
// 빈으로 등록되지 않는다(실측: CI에서 NoSuchBeanDefinitionException).
@SpringBootApplication(scanBasePackages = {"org.example.cs_study.payment", "org.example.cs_study.common"})
@EnableJpaRepositories(basePackages = {"org.example.cs_study.payment", "org.example.cs_study.common"})
@EntityScan(basePackages = {"org.example.cs_study.payment", "org.example.cs_study.common"})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
