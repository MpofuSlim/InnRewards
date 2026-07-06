package com.innbucks.loyaltyservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit coverage of the shared session-supersession store: the key scheme
 * matches user-service's writer ({@code auth:tokenver:<userUuid>}) and — like
 * the logout denylist — the lookup FAILS OPEN when Redis is absent (the
 * test-profile case: RedisAutoConfiguration excluded → the ObjectProvider
 * yields null), unreachable, or holds an unparseable value.
 */
class TokenVersionStoreTest {

    private static final String USER_UUID = "11111111-1111-1111-1111-111111111111";

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate t) {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(t);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisReturning(String value) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(value);
        return redis;
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentVersion_returnsStoredValue_usingSharedKeyScheme() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("auth:tokenver:" + USER_UUID)).thenReturn("7");

        TokenVersionStore store = new TokenVersionStore(providerOf(redis));

        assertEquals(Long.valueOf(7), store.currentVersion(USER_UUID));
        verify(ops).get("auth:tokenver:" + USER_UUID);
    }

    @Test
    void currentVersion_null_whenKeyAbsent() {
        TokenVersionStore store = new TokenVersionStore(providerOf(redisReturning(null)));

        assertNull(store.currentVersion(USER_UUID));
    }

    @Test
    void currentVersion_failsOpen_whenRedisTemplateAbsent() {
        // RedisAutoConfiguration excluded -> no StringRedisTemplate bean ->
        // ObjectProvider yields null. Must not throw and must not block.
        TokenVersionStore store = new TokenVersionStore(providerOf(null));

        assertNull(store.currentVersion(USER_UUID));
    }

    @Test
    void currentVersion_failsOpen_whenValueNotNumeric() {
        TokenVersionStore store = new TokenVersionStore(providerOf(redisReturning("not-a-number")));

        assertNull(store.currentVersion(USER_UUID));
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentVersion_failsOpen_whenRedisThrows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        TokenVersionStore store = new TokenVersionStore(providerOf(redis));

        assertNull(store.currentVersion(USER_UUID));
    }
}
