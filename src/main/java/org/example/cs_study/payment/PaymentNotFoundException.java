package org.example.cs_study.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long paymentId) {
        super("존재하지 않는 결제입니다: paymentId=" + paymentId);
    }
}
