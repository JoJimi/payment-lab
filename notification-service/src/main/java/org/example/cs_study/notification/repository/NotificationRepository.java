package org.example.cs_study.notification.repository;

import org.example.cs_study.notification.domain.Notification;

/** 알림 저장소 포트. 실제 구현은 {@link org.example.cs_study.notification.repository.adapter.NotificationRepositoryAdapter}. */
public interface NotificationRepository {

    Notification save(Notification notification);
}
