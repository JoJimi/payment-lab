package org.example.cs_study.order.repository.adapter;

import java.util.Optional;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.repository.OrderRepository;
import org.example.cs_study.order.repository.SpringDataOrderRepository;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepositoryAdapter implements OrderRepository {

    private final SpringDataOrderRepository springDataOrderRepository;

    OrderRepositoryAdapter(SpringDataOrderRepository springDataOrderRepository) {
        this.springDataOrderRepository = springDataOrderRepository;
    }

    @Override
    public Order save(Order order) {
        return springDataOrderRepository.save(order);
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return springDataOrderRepository.findById(orderId);
    }
}
