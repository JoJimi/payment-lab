package org.example.cs_study.common.exception;

import lombok.Getter;

/** 모든 도메인 커스텀 예외의 공통 상위 타입. {@link ErrorCode}로 HTTP 상태/에러 코드를 결정한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    // 동적인 값(orderId 등)을 넣은 커스텀 메시지가 필요할 때
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    // 원인 예외를 보존해야 할 때 (예: DB 제약 위반)
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
