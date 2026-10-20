package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.repository.MerchantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The organization hat is worn beside a shop pin only when the organization owns
 * the pinned merchant. Every other answer — including a failure — is "no", and
 * nothing escapes: the filter would turn an exception into a 401.
 */
class ShopPinOrganizationCheckTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID OWNER_ORG = UUID.randomUUID();

    private MerchantRepository merchants;
    private SimpleMeterRegistry registry;
    private ShopPinOrganizationCheck check;

    @BeforeEach
    void setUp() {
        merchants = mock(MerchantRepository.class);
        registry = new SimpleMeterRegistry();
        check = new ShopPinOrganizationCheck(merchants, registry);
    }

    private double count(String outcome) {
        return registry.get(ShopPinOrganizationCheck.METRIC).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("the organization that owns the pinned merchant may wear its hat")
    void owner_matches() {
        when(merchants.findOrganizationIdById(MERCHANT)).thenReturn(Optional.of(OWNER_ORG));

        assertThat(check.organizationOwnsPin(OWNER_ORG, MERCHANT)).isTrue();
        assertThat(count("org_owns_pin")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("any other organization may not")
    void otherOrganization_doesNotMatch() {
        when(merchants.findOrganizationIdById(MERCHANT)).thenReturn(Optional.of(OWNER_ORG));

        assertThat(check.organizationOwnsPin(UUID.randomUUID(), MERCHANT)).isFalse();
        assertThat(count("withheld")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unknown or unowned merchant matches nobody")
    void unknownOrUnowned_matchesNobody() {
        when(merchants.findOrganizationIdById(MERCHANT)).thenReturn(Optional.empty());

        assertThat(check.organizationOwnsPin(OWNER_ORG, MERCHANT)).isFalse();
    }

    @Test
    @DisplayName("a missing side is refused without a lookup")
    void missingSide_isRefusedWithoutALookup() {
        assertThat(check.organizationOwnsPin(null, MERCHANT)).isFalse();
        assertThat(check.organizationOwnsPin(OWNER_ORG, null)).isFalse();
        verifyNoInteractions(merchants);
        assertThat(count("withheld")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a failed lookup is a refusal, never an exception")
    void failure_isARefusal() {
        when(merchants.findOrganizationIdById(any())).thenThrow(new IllegalStateException("pool exhausted"));

        assertThat(check.organizationOwnsPin(OWNER_ORG, MERCHANT)).isFalse();
        assertThat(count("lookup_failed")).isEqualTo(1.0);
    }
}
