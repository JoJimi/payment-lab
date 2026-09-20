package org.example.cs_study.payment.repository.adapter;

import java.util.Optional;
import org.example.cs_study.payment.domain.Payment;
import org.example.cs_study.payment.repository.PaymentRepository;
import org.example.cs_study.payment.repository.SpringDataPaymentRepository;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepositoryAdapter implements PaymentRepository {

    private final SpringDataPaymentRepository springDataPaymentRepository;

    PaymentRepositoryAdapter(SpringDataPaymentRepository springDataPaymentRepository) {
        this.springDataPaymentRepository = springDataPaymentRepository;
    }

    @Override
    public Payment save(Payment payment) {
        return springDataPaymentRepository.save(payment);
    }

    @Override
    public Payment saveAndFlush(Payment payment) {
        return springDataPaymentRepository.saveAndFlush(payment);
    }

    @Override
    public Optional<Payment> findById(Long paymentId) {
        return springDataPaymentRepository.findById(paymentId);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return springDataPaymentRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public long count() {
        return springDataPaymentRepository.count();
    }
}
