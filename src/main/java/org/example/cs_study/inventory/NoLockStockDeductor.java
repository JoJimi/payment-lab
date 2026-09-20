package org.example.cs_study.inventory;

import org.example.cs_study.common.catalog.ProductNotFoundException;
import org.example.cs_study.common.inventory.InsufficientStockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 1.11-1: 락 없음 — 문제 재현용 대조군. SELECT와 UPDATE를 의도적으로 별개 문장으로
 * 나누고 그 사이에 어떤 락도 걸지 않는다. 두 트랜잭션이 같은 {@code available}을 읽고
 * 각자 계산한 절대값으로 덮어쓰면 하나의 차감이 사라지는 Lost Update가 실제로 재현된다.
 *
 * <p>JPA({@code @Version})를 거치면 Hibernate가 낙관적 락을 자동으로 강제해버려 이 재현이
 * 불가능해지므로, 일부러 {@link JdbcTemplate}으로 영속성 컨텍스트를 우회한다.
 */
@Component
class NoLockStockDeductor implements StockDeductor {

    private final JdbcTemplate jdbcTemplate;

    NoLockStockDeductor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public InventoryLockStrategy strategy() {
        return InventoryLockStrategy.NONE;
    }

    @Override
    public void deduct(Long productId, int quantity) {
        Integer available = jdbcTemplate.queryForObject(
                "SELECT available FROM inventory WHERE product_id = ?", Integer.class, productId);
        if (available == null) {
            throw new ProductNotFoundException(productId);
        }
        if (available < quantity) {
            throw new InsufficientStockException(productId);
        }

        int newAvailable = available - quantity;
        jdbcTemplate.update("UPDATE inventory SET available = ? WHERE product_id = ?", newAvailable, productId);
    }
}
