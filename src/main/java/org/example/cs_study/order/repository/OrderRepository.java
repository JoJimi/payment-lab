package org.example.cs_study.order.repository;

import java.util.Optional;
import org.example.cs_study.order.domain.Order;

/** 주문 저장소 포트. 실제 구현은 {@link org.example.cs_study.order.repository.adapter.OrderRepositoryAdapter}. */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long orderId);
}
