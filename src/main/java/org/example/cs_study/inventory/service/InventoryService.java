package org.example.cs_study.inventory.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.cs_study.inventory.domain.InventoryLockStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 4가지 재고 차감 전략(1.11) 중 하나를 설정으로 골라 위임하는 파사드.
 * {@code inventory.lock-strategy}를 바꾸면(재기동 필요 — CLAUDE.md "한 번에 한 개념만 켠다")
 * 다른 전략으로 전환된다. 1.13/1.14 벤치마크가 이 스위치를 이용한다.
 */
@Service
public class InventoryService {

    private final Map<InventoryLockStrategy, StockDeductor> deductorsByStrategy;
    private final InventoryLockStrategy activeStrategy;

    InventoryService(List<StockDeductor> deductors, @Value("${inventory.lock-strategy:OPTIMISTIC}") InventoryLockStrategy activeStrategy) {
        this.deductorsByStrategy = deductors.stream().collect(Collectors.toMap(StockDeductor::strategy, Function.identity()));
        this.activeStrategy = activeStrategy;
    }

    public void deductStock(Long productId, int quantity) {
        StockDeductor deductor = deductorsByStrategy.get(activeStrategy);
        if (deductor == null) {
            throw new IllegalStateException("등록되지 않은 락 전략입니다: " + activeStrategy);
        }
        deductor.deduct(productId, quantity);
    }

    /** 1.13/1.14 벤치마크에서 특정 전략을 명시적으로 골라 측정할 때 사용. */
    public void deductStock(InventoryLockStrategy strategy, Long productId, int quantity) {
        StockDeductor deductor = deductorsByStrategy.get(strategy);
        if (deductor == null) {
            throw new IllegalStateException("등록되지 않은 락 전략입니다: " + strategy);
        }
        deductor.deduct(productId, quantity);
    }
}
