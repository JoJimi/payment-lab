package org.example.cs_study.inventory.controller;

import org.example.cs_study.inventory.dto.response.ProductResponse;
import org.example.cs_study.inventory.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 1.15 — Look-aside 캐싱이 적용된 상품 조회. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{productId}")
    public ProductResponse getProduct(@PathVariable Long productId) {
        return productService.getProduct(productId);
    }

    /** 3.12 — 캐시 유무 성능 비교용 대조군. {@link ProductService#getProductUnprotected}는 1.17부터 존재했지만 HTTP로는 노출되지 않았다. */
    @GetMapping("/{productId}/uncached")
    public ProductResponse getProductUncached(@PathVariable Long productId) {
        return productService.getProductUnprotected(productId);
    }
}
