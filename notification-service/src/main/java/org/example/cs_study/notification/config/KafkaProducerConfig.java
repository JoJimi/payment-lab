package org.example.cs_study.notification.config;

import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * 이 서비스는 순수 컨슈머라(아무 토픽도 발행하지 않음, event-catalog.md) 지금까지
 * {@code KafkaTemplate<String, String>} 빈이 없었다. 2.16의 DLQ 오류 처리기
 * ({@code common-kafka}의 {@code KafkaErrorHandlerConfig})가 재시도를 소진한 메시지를
 * {@code <토픽>-dlt}로 보내려면 프로듀서가 필요해, 도메인 이벤트를 발행하지 않는 서비스도
 * 이 빈만큼은 갖춰야 한다.
 *
 * <p>Boot가 자동구성하는 {@code KafkaTemplate} 빈은 제네릭이 {@code KafkaTemplate<?, ?>}로
 * 선언돼 있어(spring-boot-kafka의 {@code KafkaAutoConfiguration}) 이 타입과 맞물리지
 * 않는다 — common-outbox의 {@code OutboxKafkaConfig}와 같은 이유로 직접 정의한다.
 */
@Configuration
class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, String> notificationProducerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties());
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> notificationProducerFactory) {
        return new KafkaTemplate<>(notificationProducerFactory);
    }
}
