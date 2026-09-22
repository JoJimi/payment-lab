package org.example.cs_study.order.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code unitPrice}/{@code currency}를 클라이언트가 직접 보낸다 — 2.1/2.3에서 order-service →
 * inventory-service 동기 가격 조회({@code ProductPriceLookup})를 제거한 뒤 남은 빈자리다
 * (docs/troubleshooting/04-msa-split.md #1). Payment Service는 재고를 모르듯(로드맵 부록
 * G-2 서비스 경계표) Order Service도 상품 가격의 진실 공급원이 아니다 — 클라이언트(또는
 * 미래의 BFF/카트 서비스)가 상품 조회 시점에 이미 본 가격을 그대로 실어 보내는 게, 이벤트
 * 왕복으로 가격을 비동기 조회하는 것보다 이 단계의 목표(Saga 흐름 자체)에 비해 훨씬 단순하다.
 * 가격 위변조 방어는 범위 밖으로 명시적으로 남겨둔다(2-D 이후 재검토 대상).
 */
public record CreateOrderRequest(
        @NotNull Long productId,
        @NotNull @Min(1) Integer quantity,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @NotBlank String currency) {
}
