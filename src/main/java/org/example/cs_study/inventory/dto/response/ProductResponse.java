package org.example.cs_study.inventory.dto.response;

import java.math.BigDecimal;
import org.example.cs_study.inventory.domain.Product;

public record ProductResponse(Long id, String name, BigDecimal price, String currency) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getCurrency());
    }
}
