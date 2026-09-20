package org.example.cs_study.order;

import java.math.BigDecimal;
import org.example.cs_study.common.catalog.ProductPrice;
import org.example.cs_study.common.catalog.ProductPriceLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductPriceLookup productPriceLookup;

    public OrderService(OrderRepository orderRepository, ProductPriceLookup productPriceLookup) {
        this.orderRepository = orderRepository;
        this.productPriceLookup = productPriceLookup;
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        ProductPrice price = productPriceLookup.findPrice(request.productId());
        BigDecimal totalAmount = price.price().multiply(BigDecimal.valueOf(request.quantity()));

        Order order = new Order(request.productId(), request.quantity(), totalAmount, price.currency());
        orderRepository.save(order);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderResponse.from(order);
    }
}
