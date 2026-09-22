package org.example.cs_study.notification.service;

import org.example.cs_study.event.payload.NotificationRequestedPayload;
import org.example.cs_study.notification.domain.Notification;
import org.example.cs_study.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 이메일/SMS 연동은 이 프로젝트 범위 밖이다 — "보냈다"를 로그로 남기고 기록을
 * 영속화하는 것으로 알림 발송을 모사한다(Notification.java Javadoc 참고).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void send(NotificationRequestedPayload payload) {
        Notification notification = new Notification(payload.orderId(), payload.type(), payload.message());
        notificationRepository.save(notification);
        log.info("알림 발송(mock): orderId={}, type={}, message={}", payload.orderId(), payload.type(), payload.message());
    }
}
