package org.example.cs_study.common.exception.inventory;

import org.example.cs_study.common.exception.BusinessException;
import org.example.cs_study.common.exception.ErrorCode;

public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(Long productId) {
        super(ErrorCode.INSUFFICIENT_STOCK, "재고가 부족합니다: productId=" + productId);
    }
}
