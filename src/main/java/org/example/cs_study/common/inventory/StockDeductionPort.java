package org.example.cs_study.common.inventory;

/**
 * {@code order} 패키지가 재고를 차감하는 포트. {@code inventory} 패키지가 구현체를 제공한다.
 * 하위 패키지끼리 직접 의존하지 않는다는 원칙(CLAUDE.md)에 따라, order가 inventory의
 * 락 전략 구현을 직접 import하지 않고 이 인터페이스만 바라보게 한다.
 */
public interface StockDeductionPort {

    /**
     * @throws InsufficientStockException 재고가 부족한 경우
     */
    void deduct(Long productId, int quantity);
}
