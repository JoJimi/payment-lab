package org.example.cs_study.payment.service;

import java.math.BigDecimal;

/**
 * 2.1/2.3 — 결제 요청을 받아들이기 전 주문이 실제로 존재하고 결제 가능한 상태인지 확인하는
 * 지점. 1단계에서는 이 역할을 {@code common.order.OrderPort}(같은 JVM의 Java 인터페이스)가
 * 했지만, 멀티모듈 분리로 payment-service가 order-service의 클래스를 더 이상 참조할 수 없어
 * 제거됐다.
 *
 * <p>이 인터페이스는 order-service와의 결합 지점을 하나로 모아 2-B에서 교체하기 쉽게 만드는
 * 게 목적이다 — Kafka 기반(예: order.created/payment.requested 이벤트 왕복) 또는 REST 클라이언트
 * 구현체로 바뀔 때 {@link org.example.cs_study.payment.service.PaymentService}는 손댈 필요가
 * 없다. 지금은 {@link UnimplementedOrderValidator} 하나뿐이다 — 항상 명시적으로 거부한다
 * (CodeRabbit 리뷰: 주문 검증 없이 결제를 조용히 승인 처리하면 데이터 무결성이 깨진다).
 */
interface OrderValidator {

    /**
     * @throws org.example.cs_study.common.exception.BusinessException 주문을 검증할 수 없거나
     *     (현재는 항상) 주문이 결제 요청과 맞지 않을 때
     */
    void assertValid(Long orderId, BigDecimal amount, String currency);
}
