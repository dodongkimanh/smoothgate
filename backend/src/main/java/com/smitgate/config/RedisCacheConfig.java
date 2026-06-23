package com.smitgate.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Slf4j
@Configuration
public class RedisCacheConfig implements CachingConfigurer {

    /**
     * Properly wires the CacheErrorHandler so that Redis cache errors (stale format,
     * connection timeout, serialization failure) are logged as warnings and treated as
     * cache misses instead of propagating as exceptions.
     *
     * NOTE: Simply declaring a CacheErrorHandler @Bean is NOT enough — Spring Cache
     * only uses a custom handler when returned from CachingConfigurer.errorHandler().
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache GET error [{}::{}] — treating as cache miss. Cause: {}",
                        cacheName(cache), key, ex.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Cache PUT error [{}::{}]: {}", cacheName(cache), key, ex.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache EVICT error [{}::{}]: {}", cacheName(cache), key, ex.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.warn("Cache CLEAR error [{}]: {}", cacheName(cache), ex.getMessage());
            }

            private String cacheName(Cache cache) {
                return cache != null ? cache.getName() : "unknown-cache";
            }
        };
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> {
            RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                    .disableCachingNullValues()
                    .entryTtl(Duration.ofSeconds(60))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(redisValueSerializer()));

            builder.cacheDefaults(defaultConfig)
                    .withCacheConfiguration(CacheNames.REPORT_OVERVIEW, defaultConfig.entryTtl(Duration.ofSeconds(90)))
                    .withCacheConfiguration(CacheNames.REPORT_CAMPAIGNS, defaultConfig.entryTtl(Duration.ofSeconds(90)))
                    .withCacheConfiguration(CacheNames.REPORT_CAMPAIGN_DAILY, defaultConfig.entryTtl(Duration.ofSeconds(90)))
                    .withCacheConfiguration(CacheNames.REPORT_CAMPAIGN_FUNNEL, defaultConfig.entryTtl(Duration.ofSeconds(90)))
                    .withCacheConfiguration(CacheNames.REPORT_ATTRIBUTIONS, defaultConfig.entryTtl(Duration.ofSeconds(45)))
                    .withCacheConfiguration(CacheNames.REPORT_ATTRIBUTION_QUALITY, defaultConfig.entryTtl(Duration.ofSeconds(60)))
                    .withCacheConfiguration(CacheNames.REPORT_ACCOUNT_SPEND, defaultConfig.entryTtl(Duration.ofSeconds(90)))
                    .withCacheConfiguration(CacheNames.DATASOURCE_BY_TENANT, defaultConfig.entryTtl(Duration.ofSeconds(30)))
                    .withCacheConfiguration(CacheNames.DATASOURCE_BY_TENANT_TYPE, defaultConfig.entryTtl(Duration.ofSeconds(30)));
        };
    }

    private GenericJackson2JsonRedisSerializer redisValueSerializer() {
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.smitgate")
                .allowIfSubType("java.util")
                .allowIfSubType("java.time")
                .allowIfSubType("java.math")
                .build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
