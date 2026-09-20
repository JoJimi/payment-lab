package org.example.cs_study.inventory.repository;

import java.util.Optional;
import org.example.cs_study.inventory.domain.Product;

/** 상품 저장소 포트. 실제 구현은 {@link org.example.cs_study.inventory.repository.adapter.ProductRepositoryAdapter}. */
public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(Long productId);
}
