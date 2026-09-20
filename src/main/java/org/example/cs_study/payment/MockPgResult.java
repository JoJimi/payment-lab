package org.example.cs_study.payment;

record MockPgResult(String transactionId, MockPgOutcome outcome, String errorCode) {

    static MockPgResult approved(String transactionId) {
        return new MockPgResult(transactionId, MockPgOutcome.APPROVED, null);
    }

    static MockPgResult failed(String errorCode) {
        return new MockPgResult(null, MockPgOutcome.FAILED, errorCode);
    }

    static MockPgResult timedOut() {
        return new MockPgResult(null, MockPgOutcome.TIMEOUT, null);
    }
}
