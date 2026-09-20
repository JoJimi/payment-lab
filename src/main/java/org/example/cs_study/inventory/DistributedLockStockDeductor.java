package org.example.cs_study.inventory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.example.cs_study.common.catalog.ProductNotFoundException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 1.11-4: Redisson {@code RLock} 기반 분산 락. 여러 애플리케이션 인스턴스(2단계 이후 여러 파드)가
 * 동시에 같은 상품을 건드려도 DB 트랜잭션 밖에서 먼저 직렬화한다. 락을 쥔 동안에는 어떤
 * 트랜잭션도 경합하지 않으므로 일반 save로 충분하다(버전 충돌이 날 수 없다).
 *
 * <p><b>알려진 트레이드오프:</b> REQUIRES_NEW로 재고 차감을 독립 커밋하므로, 이 메서드가 성공한
 * 뒤 호출자({@code OrderService.createOrder})의 나머지 로직이 실패해도 재고 차감은 롤백되지
 * 않는다 — 주문/재고 원자성이 깨질 수 있다는 뜻이다. 상세 배경은
 * {@code OrderService.createOrder} Javadoc 참고.
 */
@Component
class DistributedLockStockDeductor implements StockDeductor {

    // 300-way 경합(1.12)에서도 뒤쪽 대기자가 자기 차례를 기다릴 수 있을 만큼 넉넉하게 잡는다.
    private static final long WAIT_SECONDS = 10;

    private final InventoryRepository inventoryRepository;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    DistributedLockStockDeductor(
            InventoryRepository inventoryRepository,
            RedissonClient redissonClient,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.inventoryRepository = inventoryRepository;
        this.redissonClient = redissonClient;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: 락을 푸는 시점(finally)보다 DB 커밋이 먼저 끝나야 한다. 바깥 트랜잭션에
        // 합류하면(REQUIRED) 커밋은 바깥 메서드가 끝날 때까지 미뤄지는데 락은 여기서 먼저 풀려서,
        // 다음 락 획득자가 아직 커밋 안 된 값을 읽는 "unlock-before-commit" 경합이 생긴다.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public InventoryLockStrategy strategy() {
        return InventoryLockStrategy.DISTRIBUTED;
    }

    @Override
    public void deduct(Long productId, int quantity) {
        RLock lock = redissonClient.getLock("inventory-lock:" + productId);
        boolean locked;
        // 1.19: tryLock 자체가 "락을 기다린 시간"이다.
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // lease를 명시하지 않는다 — Redisson watchdog가 락을 자동 갱신한다(기본 30초마다 연장).
            // lease를 주면 watchdog가 꺼지고, DB 커넥션 대기까지 겹쳐 내부 트랜잭션이 lease보다
            // 길어지면 락이 조기 만료돼 다른 스레드가 같은 행에 들어와 Lost Update가 난다.
            locked = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재고 분산 락 대기 중 인터럽트됨", e);
        } finally {
            sample.stop(meterRegistry.timer("inventory.lock.wait", "strategy", "DISTRIBUTED"));
        }
        if (!locked) {
            throw new InventoryLockTimeoutException(productId);
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Inventory inventory =
                        inventoryRepository.findByProductId(productId).orElseThrow(() -> new ProductNotFoundException(productId));
                inventory.deduct(quantity);
                inventoryRepository.save(inventory);
            });
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
