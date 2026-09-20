package org.example.cs_study.inventory;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, BigDecimal price, String currency) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getCurrency());
    }
}
