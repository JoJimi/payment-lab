package org.example.cs_study.common.catalog;

import java.math.BigDecimal;

/** {@code order} 패키지가 {@code inventory}의 {@code Product} 엔티티를 직접 참조하지 않기 위한 DTO. */
public record ProductPrice(Long productId, BigDecimal price, String currency) {
}
