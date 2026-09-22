package org.example.cs_study.inventory.repository;

import java.util.Optional;
import org.example.cs_study.inventory.domain.Inventory;

/** 재고 저장소 포트. 실제 구현은 {@link org.example.cs_study.inventory.repository.adapter.InventoryRepositoryAdapter}. */
public interface InventoryRepository {

    Optional<Inventory> findByProductId(Long productId);

    /** 비관적 락 전략(1.11-2) 전용. {@code SELECT ... FOR UPDATE}를 발행한다. */
    Optional<Inventory> findByProductIdForUpdate(Long productId);

    Inventory save(Inventory inventory);

    Inventory saveAndFlush(Inventory inventory);
}
