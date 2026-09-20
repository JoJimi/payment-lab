package org.example.cs_study.inventory.repository.adapter;

import java.util.Optional;
import org.example.cs_study.inventory.domain.Product;
import org.example.cs_study.inventory.repository.ProductRepository;
import org.example.cs_study.inventory.repository.SpringDataProductRepository;
import org.springframework.stereotype.Repository;

@Repository
class ProductRepositoryAdapter implements ProductRepository {

    private final SpringDataProductRepository springDataProductRepository;

    ProductRepositoryAdapter(SpringDataProductRepository springDataProductRepository) {
        this.springDataProductRepository = springDataProductRepository;
    }

    @Override
    public Product save(Product product) {
        return springDataProductRepository.save(product);
    }

    @Override
    public Optional<Product> findById(Long productId) {
        return springDataProductRepository.findById(productId);
    }
}
