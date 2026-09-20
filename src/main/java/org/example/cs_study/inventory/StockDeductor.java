package org.example.cs_study.inventory;

import org.example.cs_study.common.inventory.InsufficientStockException;

interface StockDeductor {

    InventoryLockStrategy strategy();

    /**
     * @throws InsufficientStockException 재고가 부족한 경우
     */
    void deduct(Long productId, int quantity);
}
