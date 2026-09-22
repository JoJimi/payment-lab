package org.example.cs_study.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.example.cs_study.common.exception.inventory.InsufficientStockException;
import org.junit.jupiter.api.Test;

/** 2단계 예약→확정 모델(부록 A-4)의 {@code reserve}/{@code confirm}이 실제로 맞물리는지 검증. */
class InventoryTest {

    @Test
    void reserve하면_available은_줄고_reserved는_늘어난다() {
        Inventory inventory = new Inventory(1L, 10);

        inventory.reserve(3);

        assertThat(inventory.getAvailable()).isEqualTo(7);
        assertThat(inventory.getReserved()).isEqualTo(3);
    }

    @Test
    void confirm하면_reserved만_줄고_available은_그대로다() {
        Inventory inventory = new Inventory(1L, 10);
        inventory.reserve(3);

        inventory.confirm(3);

        assertThat(inventory.getAvailable()).isEqualTo(7);
        assertThat(inventory.getReserved()).isEqualTo(0);
    }

    @Test
    void 재고보다_많이_reserve하면_예외가_나고_아무것도_바뀌지_않는다() {
        Inventory inventory = new Inventory(1L, 5);

        assertThatThrownBy(() -> inventory.reserve(6)).isInstanceOf(InsufficientStockException.class);

        assertThat(inventory.getAvailable()).isEqualTo(5);
        assertThat(inventory.getReserved()).isEqualTo(0);
    }

    @Test
    void reserve_후_confirm까지_하면_결과적으로_available만_영구히_줄어든_것과_같다() {
        Inventory inventory = new Inventory(1L, 10);

        inventory.reserve(4);
        inventory.confirm(4);

        assertThat(inventory.getAvailable()).isEqualTo(6);
        assertThat(inventory.getReserved()).isEqualTo(0);
    }
}
