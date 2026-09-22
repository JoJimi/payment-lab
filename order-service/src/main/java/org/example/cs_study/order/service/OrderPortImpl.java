package org.example.cs_study.order.service;

import org.example.cs_study.common.exception.order.OrderNotFoundException;
import org.example.cs_study.common.order.OrderPort;
import org.example.cs_study.common.order.OrderView;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.domain.OrderStatus;
import org.example.cs_study.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderPortImpl implements OrderPort {

    private final OrderRepository orderRepository;

    OrderPortImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView findOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        return new OrderView(
                order.getId(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getStatus() == OrderStatus.CREATED,
                order.getStatus() == OrderStatus.PAID);
    }

    @Override
    @Transactional
    public void markPaid(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        order.markPaid();
    }
}
