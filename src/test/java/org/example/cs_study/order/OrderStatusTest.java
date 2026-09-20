package org.example.cs_study.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/domain/state-transitions.md 표를 그대로 코드로 옮겼는지 검증. Docker 불필요. */
class OrderStatusTest {

    @Test
    void CREATED에서는_PAID_FAILED_CANCELLED로만_전이할_수_있다() {
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.FAILED)).isTrue();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.CREATED.canTransitionTo(OrderStatus.CREATED)).isFalse();
    }

    @Test
    void PAID에서는_CANCELLED로만_전이할_수_있다() {
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.FAILED)).isFalse();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CREATED)).isFalse();
    }

    @Test
    void FAILED와_CANCELLED는_종료_상태라_어디로도_전이할_수_없다() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatus.FAILED.canTransitionTo(target)).isFalse();
            assertThat(OrderStatus.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void 허용되지_않는_전이를_시도하면_주문_엔티티가_예외를_던진다() {
        Order order = new Order(1L, 2, new java.math.BigDecimal("1000.0000"), "KRW");
        order.markFailed();

        org.assertj.core.api.Assertions.assertThatThrownBy(order::markPaid).isInstanceOf(IllegalStateException.class);
    }
}
