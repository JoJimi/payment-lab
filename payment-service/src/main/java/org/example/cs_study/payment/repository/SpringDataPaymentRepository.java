package org.example.cs_study.payment.repository;

import java.util.Optional;
import org.example.cs_study.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
