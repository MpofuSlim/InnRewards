package com.innbucks.loyaltyservice.security;

import java.time.Duration;

/**
 * Remembers each partner request's {@code jti} for its lifetime, so a captured
 * request cannot be sent again (V62). Production: {@link RedisPartnerJtiStore}.
 */
public interface PartnerJtiStore {

    /**
     * @return true the first time {@code key} is seen, false for a replay
     * @throws PartnerAuthException.ReplayCheckUnavailable when it cannot tell;
     *         the request is then refused, never let through
     */
    boolean firstUse(String key, Duration ttl) throws PartnerAuthException.ReplayCheckUnavailable;
}
