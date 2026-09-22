package org.example.cs_study.inventory.repository;

import java.util.Optional;
import org.example.cs_study.inventory.domain.OrderLineItem;

/** 주문 라인아이템 저장소 포트. 실제 구현은 {@link org.example.cs_study.inventory.repository.adapter.OrderLineItemRepositoryAdapter}. */
public interface OrderLineItemRepository {

    OrderLineItem save(OrderLineItem orderLineItem);

    Optional<OrderLineItem> findByOrderId(Long orderId);
}
