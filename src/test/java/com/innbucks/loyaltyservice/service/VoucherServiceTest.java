package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the two A01 access-control fixes that live in
 * {@link VoucherService}:
 * <ul>
 *   <li>{@code activeForPhone} is scoped strictly to the caller's tenant
 *       (resolves the phone's user via the tenant-keyed unique lookup, never
 *       the cross-tenant {@code findByPhoneNumber}).</li>
 *   <li>{@code revoke} enforces the per-merchant WRONG_MERCHANT guard so a
 *       merchant-scoped caller (SHOP_ADMIN) can't void another merchant's
 *       voucher inside the same tenant.</li>
 * </ul>
 */
class VoucherServiceTest {

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final VoucherBatchRepository batches = mock(VoucherBatchRepository.class);
    private final VoucherRedemptionRepository redemptions = mock(VoucherRedemptionRepository.class);
    private final VoucherTemplateService templateService = mock(VoucherTemplateService.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final NotificationGateway notifications = mock(NotificationGateway.class);
    private final FraudService fraud = mock(FraudService.class);
    private final LoyaltyMetrics metrics = mock(LoyaltyMetrics.class);

    private final VoucherService service = new VoucherService(
            vouchers, batches, redemptions, templateService, merchants, users, userService,
            notifications, fraud, metrics, new LoyaltyProperties(null, null, null, null));

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();
    private static final String PHONE = "+263770000111";

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAsMerchant(UUID merchantId) {
        var auth = new UsernamePasswordAuthenticationToken(
                "caller@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_SHOP_ADMIN")));
        auth.setDetails(new CallerDetails(merchantId, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // --- Fix 1: activeForPhone tenant scoping ----------------------------------

    @Test
    void activeForPhone_scopesToTenant_neverCrossTenantLookup() {
        Pageable pageable = PageRequest.of(0, 20);
        // No projection for this phone in the caller's tenant → empty page.
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.empty());

        Page<Dtos.VoucherResponse> result = service.activeForPhone(TENANT, PHONE, pageable);

        assertThat(result.getContent()).isEmpty();
        // The whole point of the fix: the phone→user resolution is tenant-keyed,
        // so the cross-tenant aggregate lookups are never reached.
        verify(users).findByTenantIdAndPhoneNumber(TENANT, PHONE);
        verify(users, never()).findByPhoneNumber(anyString());
        verify(vouchers, never()).findByAssignedUserIdInAndStatusIn(any(), any(), any());
    }

    @Test
    void activeForPhone_returnsOnlyThatTenantsUsersVouchers() {
        Pageable pageable = PageRequest.of(0, 20);
        LoyaltyUser u = new LoyaltyUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(TENANT);
        u.setPhoneNumber(PHONE);
        when(users.findByTenantIdAndPhoneNumber(TENANT, PHONE)).thenReturn(Optional.of(u));

        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(TENANT);
        v.setMerchantId(MERCHANT_A);
        v.setTemplateId(UUID.randomUUID());
        v.setCode("VCH-ABCD1234");
        v.setAssignedUserId(u.getId());
        when(vouchers.findByAssignedUserIdAndStatusIn(any(UUID.class), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(v), pageable, 1));

        Page<Dtos.VoucherResponse> result = service.activeForPhone(TENANT, PHONE, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).code()).isEqualTo("VCH-ABCD1234");
        // Vouchers are pulled for the tenant-scoped user id, not a cross-tenant set.
        verify(vouchers).findByAssignedUserIdAndStatusIn(org.mockito.ArgumentMatchers.eq(u.getId()), any(), any());
        verify(users, never()).findByPhoneNumber(anyString());
    }

    // --- Fix 3: revoke per-merchant guard --------------------------------------

    private Voucher voucherOwnedBy(UUID merchantId) {
        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(TENANT);
        v.setMerchantId(merchantId);
        v.setTemplateId(UUID.randomUUID());
        v.setCode("VCH-REVOKE-1");
        when(vouchers.findById(v.getId())).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void revoke_crossMerchantShopAdmin_isRejected_andDoesNotRevoke() {
        Voucher v = voucherOwnedBy(MERCHANT_A);
        authenticateAsMerchant(MERCHANT_B); // SHOP_ADMIN for a different merchant

        assertThatThrownBy(() -> service.revoke(TENANT, v.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("WRONG_MERCHANT"));

        // Guard fires before any state change — the voucher is untouched.
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
    }

    @Test
    void revoke_ownMerchantShopAdmin_succeeds() {
        Voucher v = voucherOwnedBy(MERCHANT_A);
        authenticateAsMerchant(MERCHANT_A);

        service.revoke(TENANT, v.getId());

        assertThat(v.getStatus()).isEqualTo(Voucher.Status.REVOKED);
    }

    @Test
    void revoke_tenantAdmin_noMerchantScope_bypassesGuard() {
        // MERCHANT_ADMIN / SUPER_ADMIN carry no merchantId claim → currentMerchantId()
        // is null → tenant-scoped bypass, exactly like the redeem guard.
        Voucher v = voucherOwnedBy(MERCHANT_A);
        // No authentication set — currentMerchantId() returns null.

        service.revoke(TENANT, v.getId());

        assertThat(v.getStatus()).isEqualTo(Voucher.Status.REVOKED);
    }

    @Test
    void revoke_wrongTenant_stillRejectedFirst() {
        Voucher v = voucherOwnedBy(MERCHANT_A);
        authenticateAsMerchant(MERCHANT_A);

        assertThatThrownBy(() -> service.revoke(UUID.randomUUID(), v.getId()))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(ex -> assertThat(((LoyaltyException) ex).getCode()).isEqualTo("CROSS_TENANT"));
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
    }
}
