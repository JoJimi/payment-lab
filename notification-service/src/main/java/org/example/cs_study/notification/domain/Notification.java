package org.example.cs_study.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.cs_study.event.payload.NotificationType;

/**
 * notification-service의 첫 영속 엔티티(2.12) — {@code notification.requested}를 받아 기록한다.
 * 실제 이메일/SMS 연동은 이 프로젝트 범위 밖이다(학습 목적 Mock) — "보냈다는 사실을 남긴다"가
 * 이 엔티티의 전부다. {@code orderId}는 {@code order} 패키지를 참조하지 않는 소프트 참조(순수 ID).
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    public Notification(Long orderId, NotificationType type, String message) {
        this.orderId = orderId;
        this.type = type;
        this.message = message;
    }

    @PrePersist
    void onCreate() {
        this.sentAt = Instant.now();
    }
}
