package org.example.cs_study.order;

import java.math.BigDecimal;
import org.example.cs_study.common.catalog.ProductPrice;
import org.example.cs_study.common.catalog.ProductPriceLookup;
import org.example.cs_study.common.inventory.StockDeductionPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductPriceLookup productPriceLookup;
    private final StockDeductionPort stockDeductionPort;

    public OrderService(
            OrderRepository orderRepository, ProductPriceLookup productPriceLookup, StockDeductionPort stockDeductionPort) {
        this.orderRepository = orderRepository;
        this.productPriceLookup = productPriceLookup;
        this.stockDeductionPort = stockDeductionPort;
    }

    /**
     * 1단계는 즉시 차감 모델이다(선행 결정 사항). 재고 차감이 실패하면(재고 부족) 주문 자체를
     * 만들지 않는다 — 보상 트랜잭션은 2단계 Saga의 몫이라 지금은 필요 없다.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        ProductPrice price = productPriceLookup.findPrice(request.productId());
        stockDeductionPort.deduct(request.productId(), request.quantity());

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
