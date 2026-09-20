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
     *
     * <p><b>알려진 트레이드오프:</b> {@code stockDeductionPort}의 OPTIMISTIC/DISTRIBUTED 구현은
     * 내부적으로 {@code PROPAGATION_REQUIRES_NEW}를 써서 이 메서드의 트랜잭션과 별개로 즉시
     * 커밋한다(각 구현체 주석 참고 — 재시도 시 stale 버전을 읽는 문제, unlock-before-commit 경합을
     * 막기 위함). 그 결과 재고 차감이 커밋된 *이후* {@code orderRepository.save(order)}가 실패하면
     * (제약 위반 등) 재고 차감은 롤백되지 않고 주문만 안 만들어지는 원자성 깨짐이 생길 수 있다.
     * 1단계에서는 이 확률을 감수한다 — 보상(2단계 Saga)이 이 문제의 정식 해법이다.
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
