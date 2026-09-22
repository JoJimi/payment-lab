package org.example.cs_study.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.NotificationRequestedPayload;
import org.example.cs_study.event.payload.NotificationType;
import org.example.cs_study.notification.domain.Notification;
import org.example.cs_study.notification.repository.SpringDataNotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link NotificationRequestedListener}가 이 서비스의 첫 실제 소비 경로를 증명한다(2.12) —
 * {@code notification.requested}를 받아 {@link Notification} 행을 남긴다.
 *
 * <p>메시지 발행은 앱 컨텍스트의 {@code KafkaTemplate} 빈을 쓰지 않고 이 테스트가 직접
 * {@link DefaultKafkaProducerFactory}로 만든다 — 이 서비스는 순수 컨슈머라 common-outbox의
 * {@code OutboxKafkaConfig} 같은 concrete-typed {@code KafkaTemplate<String, String>} 빈이
 * 없다(Boot 자동구성 빈은 와일드카드 제네릭이라 이 필드 타입과 안 맞는다, 2.8에서 이미 겪은
 * 문제). 다른 서비스들의 통합 테스트가 raw {@code Consumer}를 직접 만드는 것과 대칭이다.
 */
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = "notification.requested")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "spring.kafka.consumer.group-id=notification-service-test",
            "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.auto-offset-reset=earliest"
        })
class NotificationRequestedListenerTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("notification_service_test")
            .withUsername("cs")
            .withPassword("cs123");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // 이 테스트는 이 서비스 전용 임시 DB를 쓰므로(공유 payment_lab_inventory가 아님) 기본
        // 이력 테이블 이름을 그대로 써도 충돌이 없다 — application.yml의 별도 테이블 설정을
        // 덮어써 단순하게 유지한다.
        registry.add("spring.flyway.table", () -> "flyway_schema_history");
    }

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SpringDataNotificationRepository notificationRepository;

    @Test
    void notification_requested를_받으면_알림_기록을_남긴다() {
        Long orderId = 888L;
        NotificationRequestedPayload payload =
                new NotificationRequestedPayload(orderId, NotificationType.ORDER_COMPLETED, "주문이 완료됐습니다");
        String json = objectMapper.writeValueAsString(EventEnvelopeFactory.create(EventType.NOTIFICATION_REQUESTED, payload));

        // KafkaTemplate은 AutoCloseable이 아니라 try-with-resources로 못 감싼다 — 테스트
        // 클래스당 발행이 이 한 번뿐이라 프로듀서를 명시적으로 안 닫아도 JVM 종료 시 정리된다.
        createProducer().send("notification.requested", orderId.toString(), json);

        awaitNotificationSaved(orderId);
    }

    private KafkaTemplate<String, String> createProducer() {
        var producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    private void awaitNotificationSaved(Long orderId) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            java.util.List<Notification> all = notificationRepository.findAll();
            boolean found = all.stream().anyMatch(n -> n.getOrderId().equals(orderId));
            if (found) {
                assertThat(all).extracting(Notification::getType).contains(NotificationType.ORDER_COMPLETED);
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("orderId=" + orderId + "의 알림 기록이 시간 내에 저장되지 않았습니다");
    }
}
