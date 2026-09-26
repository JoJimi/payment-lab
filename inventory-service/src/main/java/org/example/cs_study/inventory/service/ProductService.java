package org.example.cs_study.inventory.service;

import java.util.concurrent.atomic.AtomicInteger;
import org.example.cs_study.common.exception.catalog.ProductNotFoundException;
import org.example.cs_study.inventory.domain.Product;
import org.example.cs_study.inventory.dto.response.ProductResponse;
import org.example.cs_study.inventory.repository.ProductRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1.15 — 상품 조회 Look-aside 캐싱. 재고(available/reserved)는 절대 캐싱하지 않는다 —
 * 그 판단 근거는 docs/decisions/0011-cache-scope-product-not-inventory.md.
 *
 * <p>1.17 — Cache Stampede 재현/방어 비교용으로 같은 조회를 두 캐시 이름으로 노출한다.
 * {@link #getProductUnprotected}는 무방어(대조군), {@link #getProduct}는 {@code sync = true}로
 * 캐시 미스 시 첫 스레드만 DB를 때리고 나머지는 그 결과를 기다리게 한다. 1단계는 단일
 * 인스턴스 모놀리식이라 JVM 로컬 동기화(sync=true)로 충분하다 — 2단계 이후 인스턴스가
 * 여러 개가 되면 1.11의 분산 락과 같은 방식이 필요해진다.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final AtomicInteger dbHitCount = new AtomicInteger();

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cacheable(cacheNames = "products-unprotected")
    @Transactional(readOnly = true)
    public ProductResponse getProductUnprotected(Long productId) {
        return fetch(productId);
    }

    @Cacheable(cacheNames = "products", sync = true)
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long productId) {
        return fetch(productId);
    }

    /**
     * 3.12 — 캐시 유무 비교의 진짜 대조군. {@link #getProductUnprotected}는 이름과 달리
     * {@code products-unprotected} 캐시에 걸려 있어(1.17, Stampede 재현용) 반복 호출이
     * 캐시를 우회하지 못한다 — 캐시 A/B 벤치마크는 어노테이션이 아예 없는 이 메서드를 써야 한다
     * (CodeRabbit 리뷰, PR #97).
     */
    @Transactional(readOnly = true)
    public ProductResponse getProductUncached(Long productId) {
        return fetch(productId);
    }

    private ProductResponse fetch(Long productId) {
        dbHitCount.incrementAndGet();
        Product product = productRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
        return ProductResponse.from(product);
    }

    /** 테스트 전용 — 캐시를 우회해 실제 DB 조회가 몇 번 일어났는지 센다 (1.17). */
    int dbHitCount() {
        return dbHitCount.get();
    }

    void resetDbHitCount() {
        dbHitCount.set(0);
    }
}
