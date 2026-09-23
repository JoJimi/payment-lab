package org.example.cs_study.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * order-service가 발행한 {@code order.created}를 구독해 만드는 로컬 읽기 모델(2.12).
 *
 * <p>{@code payment.completed}(재고 예약을 트리거하는 이벤트)에는 {@code productId}/
 * {@code quantity}가 실려 있지 않다 — Payment Service는 재고를 모른다는 서비스 경계
 * 원칙(로드맵 부록 G-2) 때문에 일부러 그렇게 설계했다. 그래서 inventory-service는
 * "이 주문이 무엇을, 몇 개 샀는지"를 스스로 기억해뒀다가 나중에 {@code payment.completed}가
 * 오면 꺼내 쓴다 — 동기 조회 대신 선행 이벤트({@code order.created})로 필요한 데이터를
 * 미리 자신의 DB로 가져오는 전형적인 이벤트 기반 로컬 캐시 패턴이다.
 */
@Entity
@Table(name = "order_line_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLineItem {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * {@code payment.completed} 처리(2.12)가 실제로 재고를 예약/확정했는지 — {@code
     * order.cancelled}가 뒤늦게 와서 예약을 되돌려야 할 때(2.15 Saga 타임아웃) 이걸 봐야
     * 한다. 예약 전에 취소가 먼저 오면 여전히 false로 남고, 그러면 되돌릴 것도 없다.
     */
    @Column(nullable = false)
    private boolean reserved = false;

    /**
     * {@code order.cancelled}를 이미 처리했는지 — {@code order.cancelled}와 {@code
     * payment.completed}는 서로 다른 토픽이라 어느 쪽이 먼저 올지 Kafka가 보장하지 않는다
     * (2.15 CodeRabbit 리뷰). 취소가 예약보다 먼저 도착하면 이 플래그로 표시해 두고, 뒤늦게
     * 오는 예약 시도를 {@link org.example.cs_study.inventory.listener.PaymentCompletedListener}가
     * 건너뛰게 한다 — 예약했다가 바로 또 되돌릴 필요가 없다.
     */
    @Column(nullable = false)
    private boolean cancelled = false;

    public OrderLineItem(Long orderId, Long productId, Integer quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public void markReserved() {
        this.reserved = true;
    }

    public void markCancelled() {
        this.cancelled = true;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
