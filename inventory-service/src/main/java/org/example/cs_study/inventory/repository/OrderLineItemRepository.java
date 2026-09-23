package org.example.cs_study.inventory.repository;

import java.util.Optional;
import org.example.cs_study.inventory.domain.OrderLineItem;

/** 주문 라인아이템 저장소 포트. 실제 구현은 {@link org.example.cs_study.inventory.repository.adapter.OrderLineItemRepositoryAdapter}. */
public interface OrderLineItemRepository {

    OrderLineItem save(OrderLineItem orderLineItem);

    Optional<OrderLineItem> findByOrderId(Long orderId);

    /**
     * 비관적 락(SELECT ... FOR UPDATE) — {@code order.cancelled}와 {@code payment.completed}가
     * 겹쳐 실행되면, 잠금 없이는 취소 리스너가 {@code reserved==false}를 보고 재고 반환을
     * 건너뛰는 동안 결제 리스너가 {@code cancelled==false}를 보고 예약을 진행하는 레이스가
     * 가능하다(2.15 CodeRabbit 리뷰) — 두 리스너 모두 이 행을 먼저 잠근 뒤 플래그를 확인해
     * 직렬화한다.
     */
    Optional<OrderLineItem> findByOrderIdForUpdate(Long orderId);
}
