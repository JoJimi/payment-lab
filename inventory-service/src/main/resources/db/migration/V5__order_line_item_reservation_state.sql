-- 2.15 CodeRabbit 리뷰: Saga 타임아웃으로 뒤늦게 취소되는 주문을 처리하려면 "이 주문이 실제로
-- 재고를 예약/확정했는지"를 inventory-service가 스스로 알고 있어야 한다(OrderLineItem.java의
-- reserved/cancelled 플래그 참고). order.cancelled와 payment.completed는 서로 다른 토픽이라
-- 어느 쪽이 먼저 올지 보장되지 않으므로 두 플래그로 최종 상태를 맞춘다.
ALTER TABLE order_line_item
    ADD COLUMN reserved  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT false;
