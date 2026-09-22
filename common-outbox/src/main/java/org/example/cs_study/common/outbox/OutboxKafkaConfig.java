package org.example.cs_study.common.outbox;

import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Boot가 자동구성하는 {@code KafkaTemplate} 빈은 제네릭이 {@code KafkaTemplate<?, ?>}로
 * 선언돼 있어(spring-boot-kafka의 {@code KafkaAutoConfiguration}), {@link OutboxRelay}가
 * 요구하는 {@code KafkaTemplate<String, String>}과 타입이 맞물리지 않는다(실측: CI에서
 * {@code NoSuchBeanDefinitionException}). 그래서 여기서 구체 타입으로 직접 정의한다 —
 * 자동구성 쪽은 {@code @ConditionalOnMissingBean(KafkaTemplate.class)}라 이 빈이 있으면
 * 자동으로 비활성화된다.
 */
@Configuration
class OutboxKafkaConfig {

    @Bean
    ProducerFactory<String, String> outboxProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties());
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
