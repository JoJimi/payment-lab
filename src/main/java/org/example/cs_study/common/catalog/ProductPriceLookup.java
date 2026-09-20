package org.example.cs_study.common.catalog;

/**
 * {@code order} 패키지가 상품 가격을 조회하는 포트. {@code inventory} 패키지가 구현체를 제공한다.
 * 하위 패키지끼리 직접 의존하지 않는다는 원칙(CLAUDE.md)에 따라, order가 inventory의
 * {@code Product} 엔티티를 직접 import하지 않고 이 인터페이스만 바라보게 한다.
 */
public interface ProductPriceLookup {

    ProductPrice findPrice(Long productId);
}
