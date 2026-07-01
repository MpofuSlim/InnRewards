package com.innbucks.loyaltyservice.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pure-unit coverage of the shared logout denylist: the key scheme matches
 * user-service's writer, and — critically — the check FAILS OPEN when Redis is
 * absent (the test-profile case: RedisAutoConfiguration excluded → the
 * ObjectProvider yields null) or unreachable, so a Redis blip never 401s every
 * authenticated request and the @SpringBootTest contexts load without Redis.
 */
class RevokedTokenDenylistTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate t) {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(t);
        return p;
    }

    @Test
    void revoked_whenTokenHashPresent_andUsesSharedKeyScheme() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(true);
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(providerOf(redis));

        assertTrue(denylist.isRevoked("some.jwt.token"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redis).hasKey(key.capture());
        // auth:revoked: (13) + lowercase SHA-256 hex (64) = 77
        assertTrue(key.getValue().startsWith("auth:revoked:"),
                "key must use the shared denylist prefix, was: " + key.getValue());
        assertEquals(13 + 64, key.getValue().length());
        assertTrue(key.getValue().substring(13).matches("[0-9a-f]{64}"),
                "hash must be lowercase hex SHA-256");
    }

    @Test
    void notRevoked_whenTokenHashAbsent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(false);
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(providerOf(redis));

        assertFalse(denylist.isRevoked("some.jwt.token"));
    }

    @Test
    void failsOpen_whenRedisTemplateAbsent() {
        // RedisAutoConfiguration excluded -> no StringRedisTemplate bean ->
        // ObjectProvider yields null. Must not throw and must not block.
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(providerOf(null));

        assertFalse(denylist.isRevoked("some.jwt.token"));
    }

    @Test
    void failsOpen_whenRedisThrows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        RevokedTokenDenylist denylist = new RevokedTokenDenylist(providerOf(redis));

        assertFalse(denylist.isRevoked("some.jwt.token"));
    }
}
