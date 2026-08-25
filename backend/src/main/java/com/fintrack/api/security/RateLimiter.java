package com.fintrack.api.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed-window request counting, backed by Redis.
 *
 * <h2>Why Redis and not a local map</h2>
 * A counter in application memory is per-instance, so two replicas behind a load balancer
 * give an attacker twice the allowance, and a restart resets it entirely. Redis keeps one
 * counter that every instance shares.
 *
 * <h2>Fixed window, and what it costs</h2>
 * A fixed window is one INCR and one EXPIRE - cheap, and correct enough for slowing down
 * credential stuffing. Its known weakness is the boundary: a caller can spend the full quota
 * at the end of one window and again at the start of the next, so the true short-term burst
 * is up to twice the limit. A sliding window would close that at the cost of a sorted set
 * per caller. For login throttling the burst is not the threat - sustained guessing is - so
 * the simpler structure is the right trade.
 */
@Component
@Slf4j
public class RateLimiter {

    private static final String KEY_PREFIX = "ratelimit:";

    /**
     * Optional: without Redis the limiter fails open rather than refusing every request.
     * A rate limiter that takes the site down when its datastore blinks is a worse outage
     * than the abuse it prevents.
     */
    private final ObjectProvider<StringRedisTemplate> redisTemplate;

    public RateLimiter(ObjectProvider<StringRedisTemplate> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Records one hit against {@code bucket} and reports whether it is over the limit.
     *
     * @param bucket identifies the caller and the action, e.g. {@code login:203.0.113.7}
     * @return the outcome, including how long until the window resets
     */
    public Decision check(String bucket, int limit, Duration window) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return Decision.allowed(limit);
        }

        String key = KEY_PREFIX + bucket;
        try {
            Long count = template.opsForValue().increment(key);
            if (count == null) {
                return Decision.allowed(limit);
            }

            // Only the first hit sets the expiry. Refreshing it on every request would slide
            // the window forward indefinitely, so a caller who keeps knocking would never be
            // released - and one who stops briefly would never be forgiven either.
            if (count == 1L) {
                template.expire(key, window);
            }

            if (count > limit) {
                Long ttl = template.getExpire(key);
                long retryAfter = ttl == null || ttl < 0 ? window.toSeconds() : ttl;
                return new Decision(false, 0, retryAfter);
            }
            return new Decision(true, (int) (limit - count), 0);

        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable for bucket {}; allowing the request", bucket, ex);
            return Decision.allowed(limit);
        }
    }

    /** Clears the counter - called after a success, so a correct login is not penalised. */
    public void reset(String bucket) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.delete(KEY_PREFIX + bucket);
        } catch (RuntimeException ex) {
            log.debug("Could not reset rate limit bucket {}", bucket, ex);
        }
    }

    /**
     * @param allowed          whether the request may proceed
     * @param remaining        requests left in this window
     * @param retryAfterSeconds seconds until the window resets, when blocked
     */
    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
        static Decision allowed(int limit) {
            return new Decision(true, limit, 0);
        }
    }
}
