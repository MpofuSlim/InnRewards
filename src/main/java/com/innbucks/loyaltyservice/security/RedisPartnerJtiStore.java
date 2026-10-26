package com.innbucks.loyaltyservice.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The {@code jti} store in Redis: {@code SET key 1 NX PX ttl}, the TTL set in the
 * same command as the write (the Redis noeviction rule: every key carries a TTL
 * from birth). No Redis, an error, or no answer is "cannot tell": the request
 * is refused with a 503.
 */
@Component
public class RedisPartnerJtiStore implements PartnerJtiStore {

    private final ObjectProvider<StringRedisTemplate> redis;

    public RedisPartnerJtiStore(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    @Override
    public boolean firstUse(String key, Duration ttl) throws PartnerAuthException.ReplayCheckUnavailable {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            throw new PartnerAuthException.ReplayCheckUnavailable(null);
        }
        Boolean first;
        try {
            first = template.opsForValue().setIfAbsent(key, "1", ttl);
        } catch (RuntimeException redisDown) {
            throw new PartnerAuthException.ReplayCheckUnavailable(redisDown);
        }
        if (first == null) {
            throw new PartnerAuthException.ReplayCheckUnavailable(null);
        }
        return first;
    }
}
