package org.example.cs_study.inventory.repository;

import org.example.cs_study.inventory.domain.OrderLineItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataOrderLineItemRepository extends JpaRepository<OrderLineItem, Long> {
}
