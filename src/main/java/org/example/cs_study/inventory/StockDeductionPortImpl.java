package org.example.cs_study.inventory;

import org.example.cs_study.common.inventory.StockDeductionPort;
import org.springframework.stereotype.Service;

@Service
class StockDeductionPortImpl implements StockDeductionPort {

    private final InventoryService inventoryService;

    StockDeductionPortImpl(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public void deduct(Long productId, int quantity) {
        inventoryService.deductStock(productId, quantity);
    }
}
