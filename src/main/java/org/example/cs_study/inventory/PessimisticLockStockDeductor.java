package org.example.cs_study.inventory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.cs_study.common.catalog.ProductNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1.11-2: 비관적 락. {@code SELECT ... FOR UPDATE}로 행을 잠가 같은 상품에 대한 다른
 * 트랜잭션의 조회 자체를 커밋할 때까지 대기시킨다. {@code @Version}이 걸려 있어도 문제없다 —
 * 행 락이 이미 경합을 직렬화하므로 버전 충돌이 발생할 수 없다.
 */
@Component
class PessimisticLockStockDeductor implements StockDeductor {

    private final InventoryRepository inventoryRepository;
    private final MeterRegistry meterRegistry;

    PessimisticLockStockDeductor(InventoryRepository inventoryRepository, MeterRegistry meterRegistry) {
        this.inventoryRepository = inventoryRepository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public InventoryLockStrategy strategy() {
        return InventoryLockStrategy.PESSIMISTIC;
    }

    @Override
    @Transactional
    public void deduct(Long productId, int quantity) {
        // 1.19: 이 조회 자체가 행 락을 기다리는 지점이다 — 경합 중이면 여기서 블로킹된다.
        Timer.Sample sample = Timer.start(meterRegistry);
        Inventory inventory =
                inventoryRepository.findByProductIdForUpdate(productId).orElseThrow(() -> new ProductNotFoundException(productId));
        sample.stop(meterRegistry.timer("inventory.lock.wait", "strategy", "PESSIMISTIC"));
        inventory.deduct(quantity);
    }
}
