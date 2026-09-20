package org.example.cs_study.payment.client;

public record MockPgResult(String transactionId, MockPgOutcome outcome, String errorCode) {

    public static MockPgResult approved(String transactionId) {
        return new MockPgResult(transactionId, MockPgOutcome.APPROVED, null);
    }

    public static MockPgResult failed(String errorCode) {
        return new MockPgResult(null, MockPgOutcome.FAILED, errorCode);
    }

    public static MockPgResult timedOut() {
        return new MockPgResult(null, MockPgOutcome.TIMEOUT, null);
    }
}
