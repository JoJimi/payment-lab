package org.example.cs_study.mockpg;

/**
 * Mock PG 런타임 설정. HTTP({@code POST /pg/_config})로 재기동 없이 갱신 가능하다.
 * 필드는 전부 volatile — 설정을 바꾸는 스레드와 요청을 처리하는 스레드가 다르다.
 */
final class MockPgConfig {

    private volatile long delayMs = 0;
    private volatile double failureRate = 0.0;
    private volatile String forcedErrorCode = null;
    private volatile boolean forceTimeout = false;

    long delayMs() {
        return delayMs;
    }

    double failureRate() {
        return failureRate;
    }

    String forcedErrorCode() {
        return forcedErrorCode;
    }

    boolean forceTimeout() {
        return forceTimeout;
    }

    void update(Long delayMs, Double failureRate, String forcedErrorCode, Boolean forceTimeout) {
        if (delayMs != null) {
            this.delayMs = delayMs;
        }
        if (failureRate != null) {
            this.failureRate = failureRate;
        }
        if (forcedErrorCode != null) {
            this.forcedErrorCode = forcedErrorCode.isBlank() ? null : forcedErrorCode;
        }
        if (forceTimeout != null) {
            this.forceTimeout = forceTimeout;
        }
    }

    void reset() {
        delayMs = 0;
        failureRate = 0.0;
        forcedErrorCode = null;
        forceTimeout = false;
    }
}
