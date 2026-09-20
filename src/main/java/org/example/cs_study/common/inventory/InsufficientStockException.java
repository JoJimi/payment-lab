package org.example.cs_study.common.inventory;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long productId) {
        super("재고가 부족합니다: productId=" + productId);
    }
}
