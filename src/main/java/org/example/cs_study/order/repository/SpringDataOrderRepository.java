package org.example.cs_study.order.repository;

import org.example.cs_study.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataOrderRepository extends JpaRepository<Order, Long> {
}
