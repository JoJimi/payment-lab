package org.example.cs_study.inventory.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.example.cs_study.inventory.domain.OrderLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataOrderLineItemRepository extends JpaRepository<OrderLineItem, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM OrderLineItem l WHERE l.orderId = :orderId")
    Optional<OrderLineItem> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
