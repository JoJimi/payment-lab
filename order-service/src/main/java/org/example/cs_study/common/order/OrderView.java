package org.example.cs_study.common.order;

import java.math.BigDecimal;

/**
 * {@code payment} 패키지가 {@code order}의 {@code Order} 엔티티를 직접 참조하지 않기 위한 DTO.
 *
 * <p>주문 상태를 단일 {@code boolean paid}로 뭉뚱그리지 않는다 — 그러면 PAID가 아닌 상태(FAILED,
 * CANCELLED)가 전부 "결제 가능"으로 오판된다. {@code payable}(=CREATED)과
 * {@code alreadyPaid}(=PAID)를 분리해서, 결제 가능한 상태가 아닌 주문(FAILED/CANCELLED 포함)은
 * {@code payable=false}로 명확히 거부한다. 하위 패키지끼리 직접 의존하지 않는다는 원칙(CLAUDE.md)에
 * 따라 {@code order.OrderStatus} 자체는 여기로 노출하지 않는다.
 */
public record OrderView(Long orderId, BigDecimal totalAmount, String currency, boolean payable, boolean alreadyPaid) {
}
