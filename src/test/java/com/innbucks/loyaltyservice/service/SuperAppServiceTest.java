package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuperAppServiceTest {

    private WalletService walletService;
    private VoucherService voucherService;
    private TransactionService transactionService;
    private UserService userService;
    private SuperAppService superApp;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID USER_ID = UUID.fromString("d2c8f0a1-0123-4567-1234-567890123456");
    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        walletService = mock(WalletService.class);
        voucherService = mock(VoucherService.class);
        transactionService = mock(TransactionService.class);
        userService = mock(UserService.class);
        superApp = new SuperAppService(walletService, voucherService, transactionService, userService);
    }

    private LoyaltyUser user() {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(USER_ID);
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        return u;
    }

    private void stubBuild() {
        // Balance/wallets keyed by phone; vouchers/transactions keyed by LoyaltyUser id.
        when(walletService.totalBalance(PHONE)).thenReturn(new BigDecimal("5300.0000"));
        when(walletService.listForPhone(PHONE)).thenReturn(List.of());
        when(voucherService.activeForUser(USER_ID)).thenReturn(List.of());
        when(transactionService.recentForUser(USER_ID)).thenReturn(List.of());
    }

    @Test
    void dashboard_byId_resolvesViaRequire_andBuildsPayload() {
        when(userService.require(TENANT, USER_ID)).thenReturn(user());
        stubBuild();

        Dtos.UserDashboard d = superApp.dashboard(TENANT, USER_ID);

        assertThat(d.userId()).isEqualTo(USER_ID);
        assertThat(d.totalPoints()).isEqualByComparingTo("5300.0000");
        verify(userService).require(TENANT, USER_ID);
    }

    @Test
    void dashboardByPhone_resolvesViaRequireByPhone_andBuildsSamePayload() {
        when(userService.requireByPhone(TENANT, PHONE)).thenReturn(user());
        stubBuild();

        Dtos.UserDashboard d = superApp.dashboardByPhone(TENANT, PHONE);

        // Resolved by phone, but the payload's userId is still the LoyaltyUser UUID.
        assertThat(d.userId()).isEqualTo(USER_ID);
        assertThat(d.totalPoints()).isEqualByComparingTo("5300.0000");
        verify(userService).requireByPhone(TENANT, PHONE);
        // Balance is resolved from the phone on the resolved projection.
        verify(walletService).totalBalance(PHONE);
    }
}
