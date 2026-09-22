package org.example.cs_study.common.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link OutboxRelay}의 {@code @Scheduled}가 실제로 돌려면 {@code @EnableScheduling}이
 * 어딘가에 있어야 한다. 소비 서비스(order/payment/inventory)마다 각자 추가하게 하는 대신
 * 이 모듈 자체에 둬서, 의존성만 추가하면 스케줄러도 함께 켜지게 한다.
 */
@Configuration
@EnableScheduling
class OutboxSchedulingConfig {
}
