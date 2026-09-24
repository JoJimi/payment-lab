package org.example.cs_study.payment.client;

/**
 * Mock PG가 승인/거절 중 어느 쪽인지 확정할 수 없는 상황(5xx, 커넥션/읽기 타임아웃,
 * 파싱 불가능한 응답)에서 던진다 — {@link MockPgResult#timedOut()}으로 이어지던 것을
 * 예외로 바꿨다(3.2). {@link MockPgClient}가 반환값으로만 결과를 전달하면
 * CircuitBreaker가 "기술적 실패"(PG 자체가 응답을 못 줌)와 "정상적인 비즈니스
 * 결과"(카드 거절 등, {@link MockPgResult#failed}) 양쪽 모두를 예외 없는 정상 반환으로
 * 취급해 구분하지 못한다 — 서킷은 전자에만 반응해야 한다.
 */
public class MockPgUnavailableException extends RuntimeException {

    public MockPgUnavailableException(String message) {
        super(message);
    }

    public MockPgUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
