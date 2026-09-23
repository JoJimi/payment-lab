package org.example.cs_study.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * {@link KafkaErrorHandlerConfig}가 실제로 증명해야 할 것: 계속 실패하는 리스너가 재시도
 * (500ms 간격 2회, 최초 시도 포함 총 3회)를 다 소진하면 원본 레코드가 그대로
 * {@code <토픽>-dlt}에 발행된다({@link DeadLetterPublishingRecoverer}의 기본 접미사가
 * 흔히 알려진 {@code .DLT}가 아니라 소문자 하이픈 {@code -dlt}다 — spring-kafka 4.1.1
 * 실측, 처음엔 {@code .DLT}로 가정했다가 이 테스트가 실패하며 드러남). Docker 없는 임베디드
 * 브로커(spring-kafka-test)로 검증한다(common-outbox의 {@code OutboxRelayTest}와 같은 패턴).
 */
@EmbeddedKafka(
        partitions = 1,
        topics = {KafkaErrorHandlerConfigTest.TOPIC, KafkaErrorHandlerConfigTest.TOPIC + "-dlt"})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = KafkaErrorHandlerConfigTest.TestApp.class,
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.consumer.group-id=kafka-error-handler-test",
            "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class KafkaErrorHandlerConfigTest {

    static final String TOPIC = "kafka-error-handler-test.topic";
    private static final AtomicInteger attempts = new AtomicInteger();

    @SpringBootApplication
    static class TestApp {

        // OutboxKafkaConfig(common-outbox)와 같은 이유 — Boot 자동구성 KafkaTemplate은
        // 와일드카드 제네릭이라 KafkaTemplate<String, String> 주입 지점(kafkaErrorHandler,
        // 이 테스트의 발행)과 안 맞는다.
        @Bean
        ProducerFactory<String, String> testProducerFactory(KafkaProperties kafkaProperties) {
            return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties());
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> testProducerFactory) {
            return new KafkaTemplate<>(testProducerFactory);
        }

        @KafkaListener(topics = TOPIC)
        void alwaysFails(String message) {
            attempts.incrementAndGet();
            throw new IllegalStateException("일부러 실패: " + message);
        }
    }

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void 재시도를_소진한_메시지는_DLT_토픽에_그대로_쌓인다() {
        kafkaTemplate.send(TOPIC, "key-1", "boom");

        Consumer<String, String> dltConsumer = createDltConsumer();
        try {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, TOPIC + "-dlt");

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(dltConsumer, TOPIC + "-dlt", Duration.ofSeconds(10));

            assertThat(record.key()).isEqualTo("key-1");
            assertThat(record.value()).isEqualTo("boom");
        } finally {
            dltConsumer.close();
        }

        assertThat(attempts.get()).isEqualTo(3);
    }

    private Consumer<String, String> createDltConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(embeddedKafkaBroker, "dlt-test-group", true);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
    }
}
