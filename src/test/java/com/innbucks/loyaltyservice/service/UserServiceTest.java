package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private LoyaltyUserRepository users;
    private UserService service;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        service = new UserService(users, mock(WalletRepository.class),
                mock(UserServiceClient.class), mock(LoyaltyMetrics.class));
    }

    @Test
    void requireByPhone_found_returnsUser() {
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.of(u));

        assertThat(service.requireByPhone(TENANT, PHONE)).isSameAs(u);
    }

    @Test
    void requireByPhone_trimsInput_beforeLookup() {
        LoyaltyUser u = new LoyaltyUser();
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.of(u));

        assertThat(service.requireByPhone(TENANT, "  " + PHONE + "  ")).isSameAs(u);
    }

    @Test
    void requireByPhone_blank_throwsBadRequest() {
        assertThatThrownBy(() -> service.requireByPhone(TENANT, "   "))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("provide a phone number");
    }

    @Test
    void requireByPhone_null_throwsBadRequest() {
        assertThatThrownBy(() -> service.requireByPhone(TENANT, null))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    void requireByPhone_unknownInTenant_throwsNotFound() {
        // Tenant-scoped: a phone that exists only under a different tenant is
        // simply absent from this tenant's lookup -> 404 (no cross-tenant reveal).
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireByPhone(TENANT, PHONE))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("not found");
    }
}
