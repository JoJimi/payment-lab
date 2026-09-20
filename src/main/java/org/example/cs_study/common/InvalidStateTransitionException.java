package org.example.cs_study.common;

/**
 * 허용되지 않는 도메인 상태 전이 (docs/domain/state-transitions.md).
 * 일반 {@link IllegalStateException}과 분리한 이유: {@code IdempotencyAspect} 같은 내부 로직도
 * 진짜 버그 상황에서 {@code IllegalStateException}을 던지는데, 그것까지 409로 보이면
 * 500이어야 할 내부 오류가 클라이언트 오류로 위장되고 메시지까지 그대로 노출된다.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
