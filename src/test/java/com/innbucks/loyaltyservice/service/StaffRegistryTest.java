package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the STAFF_RECIPIENT registry's three load-bearing behaviours: one
 * lookup per merchant per TTL window (the money path stays off the network),
 * fail-OPEN on an unknown answer (an outage must never block tills), and the
 * whitespace-insensitive compare key it shares with SELF_EARN.
 */
class StaffRegistryTest {

    private static final UUID MERCHANT = UUID.randomUUID();

    private final UserServiceClient client = mock(UserServiceClient.class);
    private final StaffRegistry registry = new StaffRegistry(client,
            new LoyaltyProperties(null, null, null, null, null, null));

    @Test
    void staffPhone_matches_andTheSetIsCached_oneLookupPerWindow() {
        when(client.merchantStaffPhones(MERCHANT))
                .thenReturn(Optional.of(Set.of("+263771234567", "+263779999999")));

        assertThat(registry.isStaffPhone(MERCHANT, "+263771234567")).isTrue();
        assertThat(registry.isStaffPhone(MERCHANT, "+263779999999")).isTrue();
        assertThat(registry.isStaffPhone(MERCHANT, "+263772000000")).isFalse();

        // Three answers, ONE upstream call — the earn path must not pay a
        // network round-trip per transaction.
        verify(client, times(1)).merchantStaffPhones(MERCHANT);
    }

    @Test
    void unknownAnswer_failsOpen_andIsCachedForTheWindow() {
        when(client.merchantStaffPhones(MERCHANT)).thenReturn(Optional.empty());

        assertThat(registry.isStaffPhone(MERCHANT, "+263771234567")).isFalse();
        assertThat(registry.isStaffPhone(MERCHANT, "+263779999999")).isFalse();

        // The failure is cached too: an outage costs one failed call per
        // merchant per window, never one per earn.
        verify(client, times(1)).merchantStaffPhones(MERCHANT);
    }

    @Test
    void authoritativeEmptySet_isSimplyNoStaff() {
        when(client.merchantStaffPhones(MERCHANT)).thenReturn(Optional.of(Set.of()));

        assertThat(registry.isStaffPhone(MERCHANT, "+263771234567")).isFalse();
    }

    @Test
    void comparisonIsWhitespaceInsensitive_bothSides() {
        when(client.merchantStaffPhones(MERCHANT))
                .thenReturn(Optional.of(Set.of("+263 77 123 4567")));

        assertThat(registry.isStaffPhone(MERCHANT, "+263771234567")).isTrue();
        assertThat(registry.isStaffPhone(MERCHANT, " +263771234567 ")).isTrue();
    }

    @Test
    void nullInputs_answerFalse_withoutTouchingTheClient() {
        assertThat(registry.isStaffPhone(null, "+263771234567")).isFalse();
        assertThat(registry.isStaffPhone(MERCHANT, null)).isFalse();
        assertThat(registry.isStaffPhone(MERCHANT, "  ")).isFalse();
        verify(client, times(0)).merchantStaffPhones(MERCHANT);
    }
}
