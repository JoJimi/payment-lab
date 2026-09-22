package org.example.cs_study.notification.repository;

import org.example.cs_study.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataNotificationRepository extends JpaRepository<Notification, Long> {
}
