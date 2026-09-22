package org.example.cs_study.payment.service;

import java.math.BigDecimal;
import org.example.cs_study.common.exception.BusinessException;
import org.example.cs_study.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 2.1/2.3 — {@link OrderValidator}의 유일한 구현체. order-service와의 실제 연동이 2-B(Kafka
 * Saga)에서 복원되기 전까지, 모든 결제 요청을 명시적으로 거부한다(CodeRabbit 리뷰 반영).
 *
 * <p>왜 조용히 승인하지 않는가: 검증 없이 결제를 승인하면 존재하지 않거나 금액이 안 맞는
 * 주문에 대해 {@code payments} 행이 쌓인다 — 나중에 되돌릴 방법이 없는 데이터 무결성 문제다.
 * 반대로 여기서 501로 명시 거부하면, 클라이언트는 "아직 안 되는 기능"과 "요청이 잘못됨"을
 * 구분할 수 있고, 잘못된 결제 데이터는 애초에 생기지 않는다.
 */
@Component
class UnimplementedOrderValidator implements OrderValidator {

    @Override
    public void assertValid(Long orderId, BigDecimal amount, String currency) {
        throw new BusinessException(
                ErrorCode.NOT_IMPLEMENTED,
                "결제 요청 전 주문 검증이 아직 복원되지 않았습니다 (2-B Kafka Saga에서 재구현 예정): orderId=" + orderId);
    }
}
