package org.example.cs_study.payment.repository;

import java.util.Optional;
import org.example.cs_study.payment.domain.Payment;

/** 결제 저장소 포트. 실제 구현은 {@link org.example.cs_study.payment.repository.adapter.PaymentRepositoryAdapter}. */
public interface PaymentRepository {

    Payment save(Payment payment);

    Payment saveAndFlush(Payment payment);

    Optional<Payment> findById(Long paymentId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    long count();
}
