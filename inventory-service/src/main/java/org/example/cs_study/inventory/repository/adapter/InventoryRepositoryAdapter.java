package org.example.cs_study.inventory.repository.adapter;

import java.util.Optional;
import org.example.cs_study.inventory.domain.Inventory;
import org.example.cs_study.inventory.repository.InventoryRepository;
import org.example.cs_study.inventory.repository.SpringDataInventoryRepository;
import org.springframework.stereotype.Repository;

@Repository
class InventoryRepositoryAdapter implements InventoryRepository {

    private final SpringDataInventoryRepository springDataInventoryRepository;

    InventoryRepositoryAdapter(SpringDataInventoryRepository springDataInventoryRepository) {
        this.springDataInventoryRepository = springDataInventoryRepository;
    }

    @Override
    public Optional<Inventory> findByProductId(Long productId) {
        return springDataInventoryRepository.findByProductId(productId);
    }

    @Override
    public Optional<Inventory> findByProductIdForUpdate(Long productId) {
        return springDataInventoryRepository.findByProductIdForUpdate(productId);
    }

    @Override
    public Inventory save(Inventory inventory) {
        return springDataInventoryRepository.save(inventory);
    }

    @Override
    public Inventory saveAndFlush(Inventory inventory) {
        return springDataInventoryRepository.saveAndFlush(inventory);
    }
}
