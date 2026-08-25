package com.fintrack.api.config;

import com.fintrack.api.dto.analytics.CashflowResponse;
import com.fintrack.api.dto.analytics.CategoryBreakdownResponse;
import com.fintrack.api.dto.analytics.SummaryResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

/**
 * Redis-backed caching for the analytics reads.
 *
 * <h2>Why these and nothing else</h2>
 * Analytics are aggregates over a whole period: expensive to compute, read on every
 * dashboard load, and identical between reloads until a transaction changes. Transactions
 * and budgets themselves are not cached - they are cheap indexed lookups, and staleness
 * there is immediately visible to the person who just edited the row.
 *
 * <h2>Every key carries the user id</h2>
 * This is the part that has to be right. A cache key of {@code summary::2026-08-01} would
 * serve one user's totals to the next caller with the same date range. Every
 * {@code @Cacheable} in this application therefore begins its key with {@code #userId},
 * and {@link com.fintrack.api.service.AnalyticsService} takes the user id as its first
 * argument specifically so that it can.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "fintrack.cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    public static final String SUMMARY = "analytics:summary";
    public static final String BY_CATEGORY = "analytics:by-category";
    public static final String CASHFLOW = "analytics:cashflow";

    /**
     * Short TTLs, because eviction on write is the primary mechanism and this is only the
     * backstop. If an invalidation is ever missed, stale data disappears within minutes
     * rather than lingering until the key is evicted for space.
     */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          ObjectMapper objectMapper) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                // Caching a null would mean caching "this user has no data yet", which is
                // exactly the value that changes the moment they add their first transaction.
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                // A serializer per cache, bound to the concrete response type. Storing the
                // type name inside the payload instead would make the cache a deserialization
                // gadget: anything able to write to Redis could name a class to instantiate.
                .withInitialCacheConfigurations(Map.of(
                        SUMMARY, typed(base, objectMapper, SummaryResponse.class),
                        BY_CATEGORY, typed(base, objectMapper, CategoryBreakdownResponse.class),
                        CASHFLOW, typed(base, objectMapper, CashflowResponse.class)))
                .build();
    }

    private static <T> RedisCacheConfiguration typed(RedisCacheConfiguration base,
                                                     ObjectMapper objectMapper,
                                                     Class<T> type) {
        return base.serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new JacksonJsonRedisSerializer<>(objectMapper, type)));
    }
}
