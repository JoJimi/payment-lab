package org.example.cs_study.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** 클라이언트에 내려가는 표준 에러 응답 바디. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, int status, Instant timestamp, String path) {

    public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
        return new ErrorResponse(errorCode.getCode(), message, errorCode.getHttpStatus().value(), Instant.now(), path);
    }
}
