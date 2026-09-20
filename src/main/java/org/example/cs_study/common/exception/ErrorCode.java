package org.example.cs_study.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * {@link BusinessException}이 참조하는 에러 코드 카탈로그. 도메인별로 접두사를 나눠서 관리한다
 * (CMN: 공통, PROD: 상품, INV: 재고, ORD: 주문, PAY: 결제, IDMP: 멱등성).
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ============================================
    // 공통 (CMN)
    // ============================================
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "CMN001", "잘못된 입력 값입니다."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "CMN002", "허용되지 않는 상태 전이입니다."),

    // ============================================
    // 상품 (PROD)
    // ============================================
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PROD001", "존재하지 않는 상품입니다."),

    // ============================================
    // 재고 (INV)
    // ============================================
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "INV001", "재고가 부족합니다."),
    INVENTORY_LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "INV002", "재고 분산 락 획득에 실패했습니다."),

    // ============================================
    // 주문 (ORD)
    // ============================================
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORD001", "존재하지 않는 주문입니다."),

    // ============================================
    // 결제 (PAY)
    // ============================================
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY001", "존재하지 않는 결제입니다."),
    PAYMENT_ORDER_MISMATCH(HttpStatus.CONFLICT, "PAY002", "결제 요청이 주문과 일치하지 않습니다."),

    // ============================================
    // 멱등성 (IDMP)
    // ============================================
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "IDMP001", "이미 처리 중인 요청입니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "IDMP002", "멱등 키가 이전과 다른 요청 본문으로 재사용되었습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
