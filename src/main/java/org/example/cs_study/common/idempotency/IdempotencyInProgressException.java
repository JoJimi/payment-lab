package org.example.cs_study.common.idempotency;

/** 동일 멱등 키의 처리가 최대 대기 시간 안에 끝나지 않아 즉시 응답할 수 없을 때 던진다 (1.8: 3-상태 중 IN_PROGRESS). */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String idempotencyKey) {
        super("이미 처리 중인 요청입니다. 잠시 후 다시 시도하세요: " + idempotencyKey);
    }
}
