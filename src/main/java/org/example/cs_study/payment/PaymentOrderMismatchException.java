package org.example.cs_study.payment;

/** 결제 요청이 주문 상태(이미 결제됨)나 주문 금액/통화와 맞지 않을 때. */
public class PaymentOrderMismatchException extends RuntimeException {

    public PaymentOrderMismatchException(String message) {
        super(message);
    }

    public PaymentOrderMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
