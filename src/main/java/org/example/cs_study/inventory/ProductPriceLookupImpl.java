package org.example.cs_study.inventory;

import org.example.cs_study.common.catalog.ProductNotFoundException;
import org.example.cs_study.common.catalog.ProductPrice;
import org.example.cs_study.common.catalog.ProductPriceLookup;
import org.springframework.stereotype.Service;

@Service
class ProductPriceLookupImpl implements ProductPriceLookup {

    private final ProductRepository productRepository;

    ProductPriceLookupImpl(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public ProductPrice findPrice(Long productId) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
        return new ProductPrice(product.getId(), product.getPrice(), product.getCurrency());
    }
}
