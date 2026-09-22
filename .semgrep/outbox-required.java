import org.springframework.kafka.core.KafkaTemplate;

class OutboxRequiredTestFixture {

    private final KafkaTemplate<String, String> kafkaTemplate;

    OutboxRequiredTestFixture(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    void directSendIsForbidden(String topic, String key, String value) {
        // ruleid: no-direct-kafka-send
        kafkaTemplate.send(topic, key, value);
    }

    void directSendWithTwoArgsIsAlsoForbidden(String topic, String value) {
        // ruleid: no-direct-kafka-send
        kafkaTemplate.send(topic, value);
    }

    void sendOnUnrelatedTypeIsFine(NotificationSender sender, String message) {
        // ok: no-direct-kafka-send
        sender.send(message);
    }

    interface NotificationSender {
        void send(String message);
    }
}
