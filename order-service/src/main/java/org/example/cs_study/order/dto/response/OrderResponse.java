package org.example.cs_study.order.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.OrderStatus;
import org.example.cs_study.order.domain.saga.SagaStatus;

/**
 * {@code sagaStatus}는 2.20 성능 측정(PR #82 CodeRabbit 리뷰)에서 필요해져 추가했다 —
 * {@link OrderStatus}만으로는 Saga가 실제로 끝났는지 알 수 없다(정상 흐름에서 결제만
 * 끝나도 {@code PAID}가 되고, 재고 예약/알림 발행은 별도로 진행되는데 그 결과가 반영되는
 * {@code OrderStatus} 값이 따로 없다 — {@code SagaStatus.COMPLETED}가 나야 재고·알림까지
 * 전부 끝난 것이다). Saga가 아직 시작되지 않았거나 조회 시점에 동시에 삭제된 것처럼
 * 이론상 존재하지 않을 수 있는 경우를 대비해 nullable로 둔다.
 */
public record OrderResponse(
        Long id,
        Long productId,
        Integer quantity,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        SagaStatus sagaStatus) {

    public static OrderResponse from(Order order, SagaStatus sagaStatus) {
        return new OrderResponse(
                order.getId(),
                order.getProductId(),
                order.getQuantity(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getCreatedAt(),
                sagaStatus);
    }
}
