package org.example.cs_study.common.exception.inventory;

import org.example.cs_study.common.exception.BusinessException;
import org.example.cs_study.common.exception.ErrorCode;

/** 분산 락(Redisson) 획득 자체가 타임아웃됐을 때. 재고 부족과는 다른 문제라 별도 예외로 분리. */
public class InventoryLockTimeoutException extends BusinessException {

    public InventoryLockTimeoutException(Long productId) {
        super(ErrorCode.INVENTORY_LOCK_TIMEOUT, "재고 분산 락 획득에 실패했습니다: productId=" + productId);
    }
}
