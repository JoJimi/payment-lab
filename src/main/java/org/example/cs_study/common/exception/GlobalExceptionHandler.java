package org.example.cs_study.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e, HttpServletRequest request) {
        ErrorResponse response = ErrorResponse.of(e.getErrorCode(), e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(response);
    }

    // 컨트롤러의 @Valid 검증 실패(CreateOrderRequest/RequestPaymentRequest 등)도 BusinessException과
    // 같은 ErrorResponse 계약을 쓰게 한다 — 아니면 이 경우만 Spring 기본 에러 바디로 응답이 갈린다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, ErrorCode.INVALID_INPUT_VALUE.getMessage(), request.getRequestURI());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus()).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
