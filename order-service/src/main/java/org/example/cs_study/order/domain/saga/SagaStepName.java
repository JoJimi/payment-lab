package org.example.cs_study.order.domain.saga;

/** Saga 정상 흐름의 단계(로드맵 2.12: 주문 생성 → 결제 → 재고 예약 → 재고 확정 → 알림). */
public enum SagaStepName {
    PAYMENT,
    INVENTORY,
    NOTIFICATION
}
