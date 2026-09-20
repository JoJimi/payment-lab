package org.example.cs_study.inventory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.cs_study.common.catalog.ProductNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 1.11-3: 낙관적 락. {@code Inventory.version}(@Version)에 맡기고, 커밋 시 충돌이 나면
 * (동시에 읽은 다른 트랜잭션이 먼저 커밋) 짧게 재시도한다. 재시도마다
 * {@link TransactionTemplate}로 새 트랜잭션을 열어 DB의 최신 버전을 다시 읽는다 — 같은
 * 트랜잭션/영속성 컨텍스트를 재사용하면 여전히 stale한 버전을 들고 있어 재시도가 무의미해진다.
 *
 * <p>재시도 횟수는 1.14 실험 대상이라 설정으로 뺐다 (기본 3회).
 */
@Component
class OptimisticLockStockDeductor implements StockDeductor {

    private final InventoryRepository inventoryRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final int maxRetries;

    OptimisticLockStockDeductor(
            InventoryRepository inventoryRepository,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry,
            @Value("${inventory.optimistic-lock.max-retries:3}") int maxRetries) {
        this.inventoryRepository = inventoryRepository;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: OrderService처럼 이미 트랜잭션 안에서 호출되더라도 재시도마다 반드시
        // 새 트랜잭션에서 최신 버전을 다시 읽어야 한다. REQUIRED로 두면 바깥 트랜잭션에 그냥
        // 합류해서 같은 영속성 컨텍스트의 캐시된(stale) 엔티티를 계속 돌려받아 재시도가 무의미해진다.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxRetries = maxRetries;
    }

    @Override
    public InventoryLockStrategy strategy() {
        return InventoryLockStrategy.OPTIMISTIC;
    }

    @Override
    public void deduct(Long productId, int quantity) {
        // 1.19: 낙관적 락에는 "대기"가 없으니 재시도를 포함한 전체 소요 시간을 경합 비용으로 본다.
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            int attempt = 0;
            while (true) {
                try {
                    deductOnce(productId, quantity);
                    return;
                } catch (ObjectOptimisticLockingFailureException e) {
                    attempt++;
                    if (attempt >= maxRetries) {
                        throw e;
                    }
                }
            }
        } finally {
            sample.stop(meterRegistry.timer("inventory.lock.wait", "strategy", "OPTIMISTIC"));
        }
    }

    private void deductOnce(Long productId, int quantity) {
        transactionTemplate.executeWithoutResult(status -> {
            Inventory inventory =
                    inventoryRepository.findByProductId(productId).orElseThrow(() -> new ProductNotFoundException(productId));
            inventory.deduct(quantity);
            inventoryRepository.saveAndFlush(inventory); // flush 시점에 버전 충돌을 즉시 드러낸다.
        });
    }
}
