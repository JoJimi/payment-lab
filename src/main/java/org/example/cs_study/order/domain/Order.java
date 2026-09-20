package org.example.cs_study.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.cs_study.common.exception.InvalidStateTransitionException;

/**
 * {@code productId}는 {@code inventory} 패키지 엔티티를 참조하지 않는 소프트 참조(순수 ID)다.
 * 하위 패키지끼리 직접 의존하지 않는다는 원칙(CLAUDE.md)을 스키마·엔티티 레벨에서 지킨다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Order(Long productId, Integer quantity, BigDecimal totalAmount, String currency) {
        this.productId = productId;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = OrderStatus.CREATED;
    }

    public void markPaid() {
        transitionTo(OrderStatus.PAID);
    }

    public void markFailed() {
        transitionTo(OrderStatus.FAILED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "주문 상태를 %s에서 %s로 전이할 수 없습니다 (orderId=%s)".formatted(status, target, id));
        }
        this.status = target;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
