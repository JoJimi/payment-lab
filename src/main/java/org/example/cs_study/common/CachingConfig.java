package org.example.cs_study.common;

import java.util.Map;
import org.redisson.api.RedissonClient;
import org.redisson.spring.cache.CacheConfig;
import org.redisson.spring.cache.RedissonSpringCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 1.15/1.17 — Redisson 기반 Spring Cache. TTL은 프로퍼티로 빼서 테스트에서 짧게(수백 ms)
 * 오버라이드할 수 있게 한다 — Cache Stampede(1.17)는 "TTL 만료 순간"을 재현해야 하는데,
 * 운영값(분 단위)을 그대로 쓰면 테스트가 몇 분씩 걸린다.
 */
@Configuration
@EnableCaching
public class CachingConfig {

    @Bean
    public CacheManager cacheManager(RedissonClient redissonClient, @Value("${cache.products.ttl-ms:60000}") long productsTtlMs) {
        CacheConfig config = new CacheConfig(productsTtlMs, 0);
        Map<String, CacheConfig> cacheConfigMap = Map.of(
                "products", config,
                "products-unprotected", config);
        return new RedissonSpringCacheManager(redissonClient, cacheConfigMap);
    }
}
