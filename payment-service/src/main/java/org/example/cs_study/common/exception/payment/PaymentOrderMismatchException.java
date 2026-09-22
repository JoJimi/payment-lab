package org.example.cs_study.common.exception.payment;

import org.example.cs_study.common.exception.BusinessException;
import org.example.cs_study.common.exception.ErrorCode;

/** 결제 요청이 주문 상태(이미 결제됨/결제 불가 상태)나 주문 금액/통화와 맞지 않을 때. */
public class PaymentOrderMismatchException extends BusinessException {

    public PaymentOrderMismatchException(String message) {
        super(ErrorCode.PAYMENT_ORDER_MISMATCH, message);
    }

    public PaymentOrderMismatchException(String message, Throwable cause) {
        super(ErrorCode.PAYMENT_ORDER_MISMATCH, message, cause);
    }
}
