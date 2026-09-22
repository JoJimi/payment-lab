package org.example.cs_study.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.example.cs_study.event.EventEnvelopeFactory;
import org.example.cs_study.event.EventType;
import org.example.cs_study.event.payload.NotificationRequestedPayload;
import org.example.cs_study.event.payload.NotificationType;
import org.example.cs_study.notification.domain.Notification;
import org.example.cs_study.notification.repository.SpringDataNotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link NotificationRequestedListener}가 이 서비스의 첫 실제 소비 경로를 증명한다(2.12) —
 * {@code notification.requested}를 받아 {@link Notification} 행을 남긴다.
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
    KafkaTemplate<String, String> kafkaTemplate;

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

        kafkaTemplate.send("notification.requested", orderId.toString(), json);

        awaitNotificationSaved(orderId);
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
