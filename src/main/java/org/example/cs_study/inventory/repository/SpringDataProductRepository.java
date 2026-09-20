package org.example.cs_study.inventory.repository;

import org.example.cs_study.inventory.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProductRepository extends JpaRepository<Product, Long> {
}
