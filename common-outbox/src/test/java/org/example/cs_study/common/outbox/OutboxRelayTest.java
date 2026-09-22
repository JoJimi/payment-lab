package org.example.cs_study.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link OutboxRelay}가 실제로 증명해야 할 것: PENDING 행을 읽어 정확한 토픽/키/값으로
 * 발행하고, 발행에 성공한 행만 PUBLISHED로 바뀐다. Docker 없는 임베디드 브로커
 * (spring-kafka-test)를 써서 Testcontainers Kafka(2.19 몫) 없이도 검증한다.
 */
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = "order.created")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = OutboxRelayTest.TestApp.class,
        properties = {
            "app.outbox.relay.fixed-delay-ms=200",
            // @EmbeddedKafka가 기동 전에 시스템 프로퍼티 spring.embedded.kafka.brokers를 채워둔다
            // (spring-kafka-test 공식 관례) — KafkaTemplate이 이 주소로 뜨게 연결한다.
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
        })
class OutboxRelayTest {

    @SpringBootApplication
    static class TestApp {
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("outbox_relay_test")
            .withUsername("cs")
            .withPassword("cs123");

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void PENDING_행을_발행하고_PUBLISHED로_바꾼다() {
        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent("Order", "42", "order.created", "{\"orderId\":42}"));

        Consumer<String, String> consumer = createConsumer();
        try {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "order.created");

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, "order.created", Duration.ofSeconds(10));

            assertThat(record.key()).isEqualTo("42");
            assertThat(record.value()).isEqualTo("{\"orderId\":42}");
        } finally {
            consumer.close();
        }

        awaitPublished(event.getId());
    }

    private void awaitPublished(Long outboxId) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            OutboxEvent reloaded = outboxEventRepository.findById(outboxId).orElseThrow();
            if (reloaded.getStatus() == OutboxStatus.PUBLISHED) {
                assertThat(reloaded.getPublishedAt()).isNotNull();
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("outbox 행이 시간 내에 PUBLISHED로 바뀌지 않았습니다. outboxId=" + outboxId);
    }

    private Consumer<String, String> createConsumer() {
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps(embeddedKafkaBroker, "outbox-relay-test-group", true);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, String>(consumerProps)
                .createConsumer();
    }
}
