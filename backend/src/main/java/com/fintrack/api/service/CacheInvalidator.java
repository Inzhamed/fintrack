package com.fintrack.api.service;

import com.fintrack.api.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drops one user's cached analytics after a write.
 *
 * <h2>Why not {@code @CacheEvict}</h2>
 * The cache keys embed a date range - {@code <userId>:2026-08-01:2026-08-31} - so evicting
 * precisely would mean knowing every range the user has ever requested. {@code @CacheEvict}
 * offers only "this exact key" or {@code allEntries = true}, and the latter throws away
 * every other user's entries on every write, which at any real user count turns the cache
 * into a miss generator.
 *
 * <h2>SCAN, not KEYS</h2>
 * {@code KEYS} walks the entire keyspace in one blocking call; on a shared Redis that stalls
 * every other client for the duration. {@code SCAN} returns cursored batches and lets other
 * commands interleave.
 */
@Service
@Slf4j
public class CacheInvalidator {

    private static final String[] ANALYTICS_CACHES = {
            CacheConfig.SUMMARY, CacheConfig.BY_CATEGORY, CacheConfig.CASHFLOW
    };

    /** How many keys SCAN fetches per round trip. */
    private static final int SCAN_BATCH = 200;

    /**
     * Optional, so the application still starts and serves traffic when Redis is absent -
     * caching is an optimisation, not a dependency, and losing it should degrade performance
     * rather than take the API down.
     */
    private final ObjectProvider<StringRedisTemplate> redisTemplate;

    public CacheInvalidator(ObjectProvider<StringRedisTemplate> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Called after any write that changes what an aggregate would compute. */
    public void evictAnalyticsFor(UUID userId) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return;
        }

        try {
            for (String cacheName : ANALYTICS_CACHES) {
                // RedisCacheManager writes keys as "<cacheName>::<key>", and every key this
                // application generates starts with the user id.
                deleteMatching(template, cacheName + "::" + userId + ":*");
            }
        } catch (RuntimeException ex) {
            // A cache that cannot be cleared is a correctness problem, but a failed eviction
            // must not roll back the write that triggered it. The short TTL bounds how long
            // the stale entry can survive.
            log.warn("Could not evict analytics cache for user {}; entries expire within the TTL",
                    userId, ex);
        }
    }

    private void deleteMatching(StringRedisTemplate template, String pattern) {
        List<String> batch = new ArrayList<>(SCAN_BATCH);

        try (Cursor<String> cursor = template.scan(
                ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build())) {

            while (cursor.hasNext()) {
                batch.add(cursor.next());
                // Deleting in batches keeps both the round trips and the memory bounded.
                if (batch.size() >= SCAN_BATCH) {
                    template.delete(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            template.delete(batch);
        }
    }
}
