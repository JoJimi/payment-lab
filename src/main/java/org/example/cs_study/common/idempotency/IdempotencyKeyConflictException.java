package org.example.cs_study.common.idempotency;

/** 같은 멱등 키로 이전과 다른 요청 본문이 들어왔을 때. 원본 응답을 재현하면 안 되는 경우다. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String key) {
        super("멱등 키가 이전과 다른 요청 본문으로 재사용되었습니다: key=" + key);
    }
}
