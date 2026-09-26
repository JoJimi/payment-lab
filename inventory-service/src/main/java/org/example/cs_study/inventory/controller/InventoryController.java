package org.example.cs_study.inventory.controller;

import org.example.cs_study.inventory.service.InventoryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 3.12 — 락 전략(1.11) 성능 비교 전용. 운영 흐름에서는 재고 차감이 Kafka 리스너를 거쳐
 * 비동기로 일어나 order-service 응답 시간에 락 대기가 드러나지 않는다. 락 전략 자체의
 * 성능만 격리해서 재는 벤치마크 스크립트(1.13/1.14, 3.12)가 이 동기 엔드포인트를 직접 호출한다.
 *
 * <p>{@code benchmark} 프로필에서만 등록한다(CodeRabbit 리뷰, PR #97) — 정상 주문 흐름을
 * 거치지 않고 재고를 직접 건드리는 경로라, dev/운영 프로필에 항상 떠 있으면 안 된다.
 * 벤치마크 스크립트(`scripts/benchmark-lock-strategies.*`, `benchmark-optimistic-retries.*`)가
 * {@code --spring.profiles.active=dev,benchmark}로 명시적으로 켠다.
 */
@RestController
@RequestMapping("/api/inventory")
@Profile("benchmark")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/{productId}/reserve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@PathVariable Long productId, @RequestParam(defaultValue = "1") int quantity) {
        inventoryService.deductStock(productId, quantity);
    }
}
