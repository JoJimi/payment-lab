package org.example.cs_study.inventory.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.cs_study.common.exception.catalog.ProductNotFoundException;
import org.example.cs_study.common.exception.inventory.InventoryLockTimeoutException;
import org.example.cs_study.inventory.domain.Inventory;
import org.example.cs_study.inventory.domain.InventoryLockStrategy;
import org.example.cs_study.inventory.repository.InventoryRepository;
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
 *
 * <p><b>알려진 트레이드오프:</b> REQUIRES_NEW로 재고 차감을 독립 커밋하므로, 이 메서드가 성공한
 * 뒤 호출자({@code OrderService.createOrder})의 나머지 로직이 실패해도 재고 차감은 롤백되지
 * 않는다 — 주문/재고 원자성이 깨질 수 있다는 뜻이다. 상세 배경은
 * {@code OrderService.createOrder} Javadoc 참고.
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
                    // maxRetries는 "최초 시도 이후 재시도 횟수"다. 증가를 검사 뒤로 옮겨야
                    // maxRetries번 재시도(= 총 maxRetries+1회 시도)한다 — 앞뒤가 바뀌면
                    // maxRetries=1일 때 재시도를 한 번도 못 하고 즉시 예외가 새어나간다.
                    //
                    // 재시도를 다 쓰면 원본 Hibernate/Spring 예외를 그대로 던지지 않는다 —
                    // BusinessException이 아니라서 GlobalExceptionHandler가 못 잡고 스택트레이스가
                    // 그대로 노출된 500으로 샌다(실측: VUS=20 부하에서 재현). 도메인 예외로
                    // 변환해 표준 ErrorResponse(503, INV002)로 응답하게 한다.
                    if (attempt >= maxRetries) {
                        throw new InventoryLockTimeoutException(productId, e);
                    }
                    attempt++;
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
