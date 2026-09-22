package org.example.cs_study.common.exception.inventory;

import org.example.cs_study.common.exception.BusinessException;
import org.example.cs_study.common.exception.ErrorCode;

/**
 * 재고 락을 끝내 확보하지 못했을 때. 재고 부족과는 다른 문제라 별도 예외로 분리.
 * 분산 락(Redisson) 자체 타임아웃, 낙관적 락 재시도 소진 둘 다 이 예외로 수렴한다.
 */
public class InventoryLockTimeoutException extends BusinessException {

    public InventoryLockTimeoutException(Long productId) {
        super(ErrorCode.INVENTORY_LOCK_TIMEOUT, "재고 분산 락 획득에 실패했습니다: productId=" + productId);
    }

    public InventoryLockTimeoutException(Long productId, Throwable cause) {
        super(ErrorCode.INVENTORY_LOCK_TIMEOUT, "재고 낙관적 락 재시도를 모두 소진했습니다: productId=" + productId, cause);
    }
}
