package org.example.cs_study.inventory.service;

import org.example.cs_study.common.exception.inventory.InsufficientStockException;
import org.example.cs_study.inventory.domain.InventoryLockStrategy;

interface StockDeductor {

    InventoryLockStrategy strategy();

    /**
     * @throws InsufficientStockException 재고가 부족한 경우
     */
    void deduct(Long productId, int quantity);
}
