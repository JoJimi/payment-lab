package org.example.cs_study.inventory;

import java.util.List;
import org.example.cs_study.common.catalog.ProductNotFoundException;
import org.example.cs_study.common.inventory.InsufficientStockException;
import org.springframework.dao.support.DataAccessUtils;
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
        // queryForObject는 결과가 0건이면 null이 아니라 EmptyResultDataAccessException을 던진다.
        // 행이 없을 수 있는 조회이므로 queryForList + singleResult로 0건을 명시적으로 다룬다.
        List<Integer> rows =
                jdbcTemplate.queryForList("SELECT available FROM inventory WHERE product_id = ?", Integer.class, productId);
        Integer available = DataAccessUtils.singleResult(rows);
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
