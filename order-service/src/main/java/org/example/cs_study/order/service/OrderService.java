package org.example.cs_study.order.service;

import org.example.cs_study.common.exception.order.OrderNotFoundException;
import org.example.cs_study.order.domain.Order;
import org.example.cs_study.order.dto.request.CreateOrderRequest;
import org.example.cs_study.order.dto.response.OrderResponse;
import org.example.cs_study.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 2.1/2.3 — 멀티모듈 분리로 order-service는 더 이상 inventory-service의 {@code
     * ProductPriceLookup}/{@code StockDeductionPort}를 (같은 JVM의) Java 인터페이스로 호출할 수
     * 없다. 1단계에서는 이 메서드가 가격 조회 + 재고 차감 + 주문 저장을 한 트랜잭션에서 처리했지만,
     * 지금은 그 두 호출을 걷어낸 상태다 — 대체 흐름(order.created 이벤트 발행 →
     * inventory-service가 재고 예약 결과를 이벤트로 회신)은 2-B(2.6~2.12, Kafka Saga)에서
     * 구현한다. 그 전까지는 의도적으로 미구현 상태로 둔다(거짓으로 동작하는 척하지 않는다).
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        throw new UnsupportedOperationException(
                "createOrder는 2-B(Kafka Saga)에서 재구현 예정 — inventory-service 동기 호출이 2.1/2.3에서 제거됨");
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderResponse.from(order);
    }
}
