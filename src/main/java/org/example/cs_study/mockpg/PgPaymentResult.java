package org.example.cs_study.mockpg;

/** Mock PG 승인 결과. {@code status}는 APPROVED 또는 FAILED만 나온다(타임아웃은 응답 자체가 늦어지는 것으로 표현). */
record PgPaymentResult(String transactionId, String status, String errorCode) {

    static PgPaymentResult approved(String transactionId) {
        return new PgPaymentResult(transactionId, "APPROVED", null);
    }

    static PgPaymentResult failed(String errorCode) {
        return new PgPaymentResult(null, "FAILED", errorCode);
    }
}
