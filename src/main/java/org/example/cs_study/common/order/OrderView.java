package org.example.cs_study.common.order;

import java.math.BigDecimal;

/** {@code payment} 패키지가 {@code order}의 {@code Order} 엔티티를 직접 참조하지 않기 위한 DTO. */
public record OrderView(Long orderId, BigDecimal totalAmount, String currency, boolean paid) {
}
