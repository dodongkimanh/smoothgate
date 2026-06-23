package com.smitgate;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.retry.annotation.EnableRetry;

import java.time.Duration;

@SpringBootApplication
@EnableScheduling
@EnableCaching
@EnableRetry
@EnableAsync
public class SmitGateApplication {

    @Bean
    @ConditionalOnMissingBean(RedisCacheManagerBuilderCustomizer.class)
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> {
            BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                    .allowIfSubType("com.smitgate")
                    .allowIfSubType("java.util")
                    .allowIfSubType("java.time")
                    .allowIfSubType("java.math")
                    .build();

            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

            GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);
            RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                    .disableCachingNullValues()
                    .entryTtl(Duration.ofSeconds(60))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

            builder.cacheDefaults(defaultConfig);
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(SmitGateApplication.class, args);
    }
}
