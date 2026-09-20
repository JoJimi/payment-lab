package org.example.cs_study.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.cs_study.common.inventory.InsufficientStockException;

/**
 * 상품 1개당 재고 1행(1:1). {@code available}/{@code reserved} 2단 모델은 2단계
 * 예약→확정 전환(부록 A-4)을 대비한 것이고, 1단계에서는 즉시 차감만 쓴다
 * (선행 결정: "1단계는 즉시 차감, 2단계에서 예약 모델로 전환").
 *
 * <p>{@code version}은 낙관적 락(1.11) 실험용. 비관적 락/락 없음/분산 락 모드에서도
 * 컬럼은 존재하지만 그 모드들은 이 값을 검사하지 않는다 — 락 4종을 런타임에 전환
 * 가능하게 하기 위해 스키마를 공통으로 유지한다 (CLAUDE.md 예외: 락 4종은 처음부터
 * 전환 가능하게 설계).
 */
@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(nullable = false)
    private Integer available;

    @Column(nullable = false)
    private Integer reserved = 0;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Inventory(Long productId, Integer available) {
        this.productId = productId;
        this.available = available;
        this.reserved = 0;
    }

    /**
     * 검증(재고 부족)과 차감 로직 자체는 락 전략 4종이 공유한다. 전략마다 다른 건
     * "이 메서드 호출 전후로 동시성을 어떻게 막는가"뿐이다 (1.11).
     */
    public void deduct(int quantity) {
        if (this.available < quantity) {
            throw new InsufficientStockException(productId);
        }
        this.available -= quantity;
    }

    @PrePersist
    void onCreate() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
