package org.example.cs_study.common.order;

/**
 * {@code payment} 패키지가 주문을 조회/확정하는 포트. {@code order} 패키지가 구현체를 제공한다.
 * 하위 패키지끼리 직접 의존하지 않는다는 원칙(CLAUDE.md)에 따라, payment가 order의
 * {@code Order} 엔티티를 직접 import하지 않고 이 인터페이스만 바라보게 한다.
 */
public interface OrderPort {

    /**
     * @throws org.example.cs_study.order.OrderNotFoundException 존재하지 않는 주문인 경우
     */
    OrderView findOrder(Long orderId);

    /**
     * 결제 승인 시 주문을 PAID로 전이한다.
     *
     * @throws org.example.cs_study.order.OrderNotFoundException 존재하지 않는 주문인 경우
     * @throws org.example.cs_study.common.InvalidStateTransitionException PAID로 전이할 수 없는 상태인 경우
     */
    void markPaid(Long orderId);
}
