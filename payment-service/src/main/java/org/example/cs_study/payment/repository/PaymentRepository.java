package org.example.cs_study.payment.repository;

import java.util.List;
import java.util.Optional;
import org.example.cs_study.payment.domain.Payment;

/** 결제 저장소 포트. 실제 구현은 {@link org.example.cs_study.payment.repository.adapter.PaymentRepositoryAdapter}. */
public interface PaymentRepository {

    Payment save(Payment payment);

    Payment saveAndFlush(Payment payment);

    Optional<Payment> findById(Long paymentId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * 보상 트랜잭션(2.13)이 order.cancelled 수신 시 쓴다. 목록으로 받는 이유: {@code order_id}엔
     * DB 유니크 제약이 없다(FAILED/CANCELLED 이후 새 멱등키로 재시도 가능하게 열어둔 설계,
     * V2__domain_schema.sql의 {@code ux_payments_active_order} 참고) — 현재 Saga는 주문당
     * 멱등키를 한 번만 발급해 실질적으로 한 건뿐이지만, 스키마가 보장하는 바는 아니다.
     */
    List<Payment> findByOrderId(Long orderId);

    long count();
}
