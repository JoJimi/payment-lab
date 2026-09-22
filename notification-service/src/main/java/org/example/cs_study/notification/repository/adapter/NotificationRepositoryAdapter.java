package org.example.cs_study.notification.repository.adapter;

import org.example.cs_study.notification.domain.Notification;
import org.example.cs_study.notification.repository.NotificationRepository;
import org.example.cs_study.notification.repository.SpringDataNotificationRepository;
import org.springframework.stereotype.Repository;

@Repository
class NotificationRepositoryAdapter implements NotificationRepository {

    private final SpringDataNotificationRepository springDataNotificationRepository;

    NotificationRepositoryAdapter(SpringDataNotificationRepository springDataNotificationRepository) {
        this.springDataNotificationRepository = springDataNotificationRepository;
    }

    @Override
    public Notification save(Notification notification) {
        return springDataNotificationRepository.save(notification);
    }
}
