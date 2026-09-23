package org.example.cs_study.inventory.repository.adapter;

import java.util.Optional;
import org.example.cs_study.inventory.domain.OrderLineItem;
import org.example.cs_study.inventory.repository.OrderLineItemRepository;
import org.example.cs_study.inventory.repository.SpringDataOrderLineItemRepository;
import org.springframework.stereotype.Repository;

@Repository
class OrderLineItemRepositoryAdapter implements OrderLineItemRepository {

    private final SpringDataOrderLineItemRepository springDataOrderLineItemRepository;

    OrderLineItemRepositoryAdapter(SpringDataOrderLineItemRepository springDataOrderLineItemRepository) {
        this.springDataOrderLineItemRepository = springDataOrderLineItemRepository;
    }

    @Override
    public OrderLineItem save(OrderLineItem orderLineItem) {
        return springDataOrderLineItemRepository.save(orderLineItem);
    }

    @Override
    public Optional<OrderLineItem> findByOrderId(Long orderId) {
        return springDataOrderLineItemRepository.findById(orderId);
    }

    @Override
    public Optional<OrderLineItem> findByOrderIdForUpdate(Long orderId) {
        return springDataOrderLineItemRepository.findByOrderIdForUpdate(orderId);
    }
}
