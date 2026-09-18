package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Holder resolution on the ISSUE side and on the view/transfer gate — the two
 * halves of the redemption fixes that a review proved could be reverted with the
 * whole suite still green.
 *
 * <p>Those three lines were each correct and each unpinned, which is the worst
 * combination: the guards test covers redemption thoroughly but builds its rows
 * by hand (`setAssigneePhone("")`), so nothing exercised the normalisation that
 * stops such a row being WRITTEN, and nothing at all exercised
 * {@code markViewed}. A test that cannot fail is not coverage, so each case here
 * was confirmed to redden when its line is reverted.
 *
 * <ul>
 *   <li>{@code createVoucher}'s blank normalisation — revert
 *       {@code || assigneePhone.isBlank()};</li>
 *   <li>{@code requireCallerMayViewVoucher} resolving through
 *       {@code holderPhone} — revert to {@code v.getAssigneePhone()};</li>
 *   <li>{@code holderAccount}'s tenant filter — revert the
 *       {@code .filter(u -> u.getTenantId()...)}.</li>
 * </ul>
 */
class VoucherHolderResolutionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID ASSIGNED_USER = UUID.randomUUID();
    private static final String USER_PHONE = "+263786546765";

    private static final SupportedCurrencies CURRENCIES = new SupportedCurrencies("USD", "USD");

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
    private final UserService userService = mock(UserService.class);

    private VoucherService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret-value");
        when(props.voucher().defaultValidityDays()).thenReturn(365);
        service = new VoucherService(vouchers, mock(VoucherBatchRepository.class),
                mock(VoucherRedemptionRepository.class),
                mock(MerchantService.class), merchantAuthz, CURRENCIES, rules, users,
                userService, mock(NotificationGateway.class),
                mock(FraudService.class), new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                props, new ExchangeRateService(mock(ExchangeRateRepository.class),
                        CURRENCIES, new BigDecimal("25")),
                mock(org.springframework.context.ApplicationEventPublisher.class));

        when(vouchers.findByCode(anyString())).thenReturn(Optional.empty());
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of());

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Pizza Inn");
        m.setCurrency("USD");
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenReturn(m);

        when(users.findById(ASSIGNED_USER)).thenReturn(Optional.of(user(TENANT, USER_PHONE)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static LoyaltyUser user(UUID tenantId, String phone) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(ASSIGNED_USER);
        u.setTenantId(tenantId);
        u.setPhoneNumber(phone);
        u.setStatus(LoyaltyUser.Status.ACTIVE);
        return u;
    }

    /** Issue to {@code assignedUserId}, with whatever the caller sent as a phone. */
    private Dtos.IssueVoucherRequest issueToAssignedUser(String assigneePhone) {
        return new Dtos.IssueVoucherRequest(MERCHANT, null, new BigDecimal("5.00"), "USD", null,
                assigneePhone, "Sedrick Nyanyiwa", ASSIGNED_USER,
                null, null, Voucher.DeliveryChannel.WHATSAPP, null);
    }

    private Voucher saved() {
        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).save(cap.capture());
        return cap.getValue();
    }

    private static void asCustomer(String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                "customer@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new CallerDetails(null, null, phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** A live voucher held by {@link #ASSIGNED_USER} with a BLANK phone column. */
    private Voucher blankPhoneVoucher() {
        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(TENANT);
        v.setMerchantId(MERCHANT);
        v.setStatus(Voucher.Status.ISSUED);
        v.setCode("ABCD2345EFGH");
        v.setAssigneePhone("");
        v.setAssignedUserId(ASSIGNED_USER);
        when(vouchers.findByCode(v.getCode())).thenReturn(Optional.of(v));
        return v;
    }

    // =====================================================================
    // (a) The blank never gets written in the first place
    // =====================================================================

    @Test
    void issuingToAUserWithABlankPhoneStoresTheUsersOwnNumber() {
        // The shape the redemption bug was made of, created the only way a
        // client can actually create it: send assignedUserId AND an empty
        // assigneePhone. Before the normalisation the row kept "" — delivery
        // then resolved the user's number and sent them the code, and the
        // ownership check compared their phone claim against "" and refused it.
        service.issue(TENANT, issueToAssignedUser(""));

        assertThat(saved().getAssigneePhone())
                .as("a blank phone counts as absent and must be backfilled, exactly like a null one")
                .isEqualTo(USER_PHONE);
    }

    @Test
    void aWhitespaceOnlyPhoneIsBlankToo() {
        service.issue(TENANT, issueToAssignedUser("   "));

        assertThat(saved().getAssigneePhone()).isEqualTo(USER_PHONE);
    }

    @Test
    void aRealPhoneIsLeftExactlyAsSent() {
        // The other half of the rule: normalisation must not overwrite a
        // deliberate recipient. A voucher may legitimately be assigned to one
        // account and delivered to a different number.
        service.issue(TENANT, issueToAssignedUser("+263782608767"));

        assertThat(saved().getAssigneePhone()).isEqualTo("+263782608767");
    }

    // =====================================================================
    // (b) The view/transfer gate resolves the holder the same way
    // =====================================================================

    @Test
    void theAssignedUserCanViewAVoucherWhosePhoneColumnIsBlank() {
        // markViewed had NO test of any kind, so reverting this gate to the raw
        // column was free. The revert locks the assigned holder out of their own
        // voucher — the same defect as the redemption one, one endpoint over.
        Voucher v = blankPhoneVoucher();

        asCustomer(USER_PHONE);

        service.markViewed(v.getCode());

        assertThat(v.getStatus()).isEqualTo(Voucher.Status.VIEWED);
        assertThat(v.getViewedAt()).isNotNull();
    }

    @Test
    void someoneElseStillCannotViewIt() {
        // The gate has to keep refusing, or the case above would pass for the
        // uninteresting reason that it stopped checking anything.
        Voucher v = blankPhoneVoucher();

        asCustomer("+263700000000");

        assertThatThrownBy(() -> service.markViewed(v.getCode()))
                .isInstanceOf(LoyaltyException.class);
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
        assertThat(v.getViewedAt()).isNull();
    }

    // =====================================================================
    // (c) Issue refuses the cross-tenant assignment the gate also re-checks
    // =====================================================================

    @Test
    void aVoucherCannotBeAssignedToAnotherTenantsAccount() {
        // The issue-side half of the tenant rule. holderAccount re-checks it at
        // the gate (belt and braces, pinned in VoucherRedemptionGuardsTest);
        // this is the refusal that stops such a row being written at all.
        when(users.findById(ASSIGNED_USER)).thenReturn(Optional.of(user(OTHER_TENANT, USER_PHONE)));

        assertThatThrownBy(() -> service.issue(TENANT, issueToAssignedUser(null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("CROSS_TENANT"));

        verify(vouchers, never()).save(any(Voucher.class));
    }
}
