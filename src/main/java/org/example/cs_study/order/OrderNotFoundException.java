package org.example.cs_study.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long orderId) {
        super("존재하지 않는 주문입니다: orderId=" + orderId);
    }
}
