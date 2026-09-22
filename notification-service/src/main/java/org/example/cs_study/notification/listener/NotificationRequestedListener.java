package org.example.cs_study.notification.listener;

import org.example.cs_study.common.inbox.InboxService;
import org.example.cs_study.event.EventEnvelope;
import org.example.cs_study.event.EventEnvelopeReader;
import org.example.cs_study.event.TraceContext;
import org.example.cs_study.event.payload.NotificationRequestedPayload;
import org.example.cs_study.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 정상 흐름의 마지막 이음매(2.12) — notification-service가 이 프로젝트에서 처음으로
 * Kafka 컨슈머다운 컨슈머가 되는 지점이자, 처음으로 영속 대상을 갖는 지점이다(2.2/2.9에서
 * 계속 미뤄온 이유는 {@code docs/architecture/event-catalog.md} "구현 위치 (2.9)" 참고).
 */
@Component
public class NotificationRequestedListener {

    private final InboxService inboxService;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public NotificationRequestedListener(
            InboxService inboxService, ObjectMapper objectMapper, NotificationService notificationService) {
        this.inboxService = inboxService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "notification.requested")
    public void onMessage(String message) {
        EventEnvelope<NotificationRequestedPayload> envelope =
                EventEnvelopeReader.read(objectMapper, message, NotificationRequestedPayload.class);
        try (var ignored = TraceContext.restore(envelope.traceId())) {
            inboxService.processIfNew(envelope.eventId(), () -> notificationService.send(envelope.payload()));
        }
    }
}
