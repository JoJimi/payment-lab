package org.example.cs_study.common;

import java.util.Map;
import org.example.cs_study.common.catalog.ProductNotFoundException;
import org.example.cs_study.common.idempotency.IdempotencyInProgressException;
import org.example.cs_study.common.inventory.InsufficientStockException;
import org.example.cs_study.inventory.InventoryLockTimeoutException;
import org.example.cs_study.order.OrderNotFoundException;
import org.example.cs_study.payment.PaymentNotFoundException;
import org.example.cs_study.payment.PaymentOrderMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ProductNotFoundException.class, OrderNotFoundException.class, PaymentNotFoundException.class})
    public ResponseEntity<Map<String, String>> handleNotFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IdempotencyInProgressException.class, InsufficientStockException.class, PaymentOrderMismatchException.class})
    public ResponseEntity<Map<String, String>> handleConflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    // 허용되지 않는 상태 전이(docs/domain/state-transitions.md) 전용 — 일반 IllegalStateException은
    // 여기서 잡지 않는다. IdempotencyAspect 같은 내부 로직도 진짜 버그 상황에서 IllegalStateException을
    // 던지는데, 그걸 409로 보이면 500이어야 할 내부 오류가 위장되고 메시지까지 노출된다.
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleInvalidStateTransition(InvalidStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    // 분산 락 경합으로 인한 타임아웃 — 재시도 가능한 일시적 실패이지 서버 오류가 아니다.
    @ExceptionHandler(InventoryLockTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleLockTimeout(InventoryLockTimeoutException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
