package com.innbucks.loyaltyservice.service;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MerchantService}: onboarding (fees, overrides, the
 * zero-fee guard) and listing.
 *
 * <p>Every test runs as a merchant admin acting for {@link #ORG} in loyalty —
 * the only caller shape that may onboard a merchant for itself since ownership
 * moved from an email to the organization. Ownership itself is pinned in
 * {@code MerchantOrganizationOwnershipTest}.
 *
 * <p>Fees are InnBucks' to set (V67), so a test about the fee fields first
 * calls {@link #actAsInnBucks()}: a merchant admin sending them is refused
 * {@code FEES_PLATFORM_ONLY}, which the {@code feeAuthority_*} cases pin.
 */
class MerchantServiceTest {

    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");

    @BeforeEach
    void actAsAMerchantAdminOfTheOrganization() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "owner@rudo.co.zw", null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_ADMIN")));
        auth.setDetails(new CallerDetails(null, null, null, UUID.randomUUID(), ORG));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** SUPER_ADMIN, the InnBucks caller that onboards a merchant with fees. */
    private static void actAsInnBucks() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin@innbucks.co.zw", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new CallerDetails(null, null, null, UUID.randomUUID(), null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static final com.innbucks.loyaltyservice.config.LoyaltyProperties PROPS =
            new com.innbucks.loyaltyservice.config.LoyaltyProperties(null, null, null, null, null, null, null);

    private static final com.innbucks.loyaltyservice.config.SupportedCurrencies CURRENCIES =
            new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD");

    private static Merchant merchant(UUID id, UUID tenantId, String name) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setTenantId(tenantId);
        m.setName(name);
        m.setCategory("Coffee");
        m.setCurrency("USD");
        m.setStatus(Merchant.Status.ACTIVE);
        return m;
    }

    @Test
    void list_returnsTheTenantsPage() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        Merchant a = merchant(UUID.randomUUID(), tenantId, "Cafe A");
        a.setOrganizationId(ORG);
        when(repo.findByTenantId(tenantId, page)).thenReturn(new PageImpl<>(List.of(a), page, 1));

        Page<Dtos.MerchantResponse> result = newService(repo).list(tenantId, page);

        assertThat(result.getContent()).extracting(Dtos.MerchantResponse::organizationId).containsExactly(ORG);
    }

    private static MerchantService newService(MerchantRepository repo) {
        return new MerchantService(repo, mock(LoyaltyRuleRepository.class), PROPS, CURRENCIES);
    }

    private static final Dtos.FeeModel PRICED =
            new Dtos.FeeModel(Merchant.FeeType.FIXED, new java.math.BigDecimal("0.25"), java.math.BigDecimal.ZERO);

    private static Dtos.MerchantRequest req(Dtos.FeeModel issued, Dtos.FeeModel redeemed) {
        return new Dtos.MerchantRequest("Cafe A", "F&B", "USD",
                Merchant.BillingCycle.MONTHLY, issued, redeemed);
    }

    @Test
    void create_acceptsFixedPlusPercentage() {
        actAsInnBucks();
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        Dtos.FeeModel mix = new Dtos.FeeModel(Merchant.FeeType.FIXED_PLUS_PERCENTAGE,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(), req(mix, mix));

        assertThat(resp.feeIssued().type()).isEqualTo(Merchant.FeeType.FIXED_PLUS_PERCENTAGE);
        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0.30");
        assertThat(resp.feeIssued().percentage()).isEqualByComparingTo("2.5");
        assertThat(resp.feeRedeemed().type()).isEqualTo(Merchant.FeeType.FIXED_PLUS_PERCENTAGE);
    }

    // --- Onboarding override: the merchant's own rule, created in the same call ---

    @Test
    void create_withoutOverride_createsNoRule() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.MerchantResponse resp = new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(), req(null, null));

        // No override -> no rule is written. The repository IS read, because the
        // zero-fee guard has to resolve what this merchant would actually be
        // billed, so assert on the write rather than on no interaction at all.
        verify(rules, never()).save(any());
        assertThat(resp.loyaltyRuleId()).isNull();
    }

    @Test
    void create_withOverride_createsTheMerchantsOwnRule() {
        actAsInnBucks();
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        UUID tenantId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(rules.save(any(LoyaltyRule.class))).thenAnswer(inv -> {
            LoyaltyRule r = inv.getArgument(0);
            r.setId(ruleId);
            return r;
        });

        Dtos.MerchantRuleOverride override = new Dtos.MerchantRuleOverride(
                null,                                    // transactionType defaults to PURCHASE
                new java.math.BigDecimal("2"),           // 2 points per unit
                null, null, "MAIN",
                new java.math.BigDecimal("5.00"),        // earning floor
                new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE, java.math.BigDecimal.ZERO, new java.math.BigDecimal("1")),
                null);                                   // redeem fee keeps inheriting

        Dtos.MerchantResponse resp = new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(tenantId, new Dtos.MerchantRequest("Cafe A", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY, null, null, override));

        assertThat(resp.loyaltyRuleId()).isEqualTo(ruleId);

        ArgumentCaptor<LoyaltyRule> saved = ArgumentCaptor.forClass(LoyaltyRule.class);
        verify(rules).save(saved.capture());
        LoyaltyRule r = saved.getValue();
        assertThat(r.getTenantId()).isEqualTo(tenantId);
        assertThat(r.getMerchantId()).isEqualTo(resp.id());          // merchant-specific, so it beats the global rule
        assertThat(r.getTransactionType()).isEqualTo(TransactionType.PURCHASE);
        assertThat(r.getPointsPerUnit()).isEqualByComparingTo("2");
        assertThat(r.getMultiplier()).isEqualByComparingTo("1");     // defaulted
        assertThat(r.getMinTransactionAmount()).isEqualByComparingTo("5.00");
        assertThat(r.getFeeIssuedType()).isEqualTo(Merchant.FeeType.PERCENTAGE);
        assertThat(r.getFeeIssuedPercentage()).isEqualByComparingTo("1");
        // Omitted side stays null = inherit the tenant standard, NOT zero.
        assertThat(r.getFeeRedeemedType()).isNull();
    }

    @Test
    void create_overrideWithBadFee_rejectsBeforeSavingTheRule() {
        actAsInnBucks();
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.MerchantRuleOverride bad = new Dtos.MerchantRuleOverride(
                null, null, null, null, null, null,
                new Dtos.FeeModel(Merchant.FeeType.FIXED,
                        new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5")),
                null);

        assertThatThrownBy(() -> new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, null, null, bad)))
                .hasMessageContaining("FIXED")
                .hasMessageContaining("percentage");
        verify(rules, never()).save(any());
    }

    @Test
    void create_overrideWithNegativeFloor_isRejected() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.MerchantRuleOverride bad = new Dtos.MerchantRuleOverride(
                null, null, null, null, null, new java.math.BigDecimal("-1"), null, null);

        assertThatThrownBy(() -> new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, null, null, bad)))
                .hasMessageContaining("minTransactionAmount");
        verify(rules, never()).save(any());
    }

    @Test
    void create_rejectsFixedWithNonZeroPercentage() {
        actAsInnBucks();
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("FIXED")
                .hasMessageContaining("percentage");
    }

    @Test
    void create_rejectsPercentageWithNonZeroFixed() {
        actAsInnBucks();
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("PERCENTAGE")
                .hasMessageContaining("fixed");
    }

    @Test
    void create_acceptsAZeroPercentFee_onBothSides() {
        actAsInnBucks();
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        Dtos.FeeModel zeroPct = new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);

        Dtos.MerchantResponse resp = newService(repo).create(UUID.randomUUID(), req(zeroPct, zeroPct));

        assertThat(resp.feeIssued().type()).isEqualTo(Merchant.FeeType.PERCENTAGE);
        assertThat(resp.feeIssued().percentage()).isEqualByComparingTo("0");
        assertThat(resp.feeRedeemed().percentage()).isEqualByComparingTo("0");
    }

    @Test
    void create_rejectsFixedPlusPercentageMissingALeg() {
        actAsInnBucks();
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED_PLUS_PERCENTAGE,
                new java.math.BigDecimal("0.30"), java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("FIXED_PLUS_PERCENTAGE");
    }

    @Test
    void create_rejectsNegativeValues() {
        actAsInnBucks();
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                new java.math.BigDecimal("-0.10"), java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining(">= 0");
    }

    @Test
    void create_nullFeeModels_defaultEntityToFixedZero() {
        actAsInnBucks();
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        // Waived, to show a waiver leaves the fee columns at their defaults.
        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Cafe A", "F&B", "USD", Merchant.BillingCycle.MONTHLY,
                        null, null, null, true, "Free by arrangement"));

        // Entity defaults: type=FIXED, fixed=0, percentage=0 (no billing impact).
        assertThat(resp.feeIssued().type()).isEqualTo(Merchant.FeeType.FIXED);
        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0");
        assertThat(resp.feeIssued().percentage()).isEqualByComparingTo("0");
    }

    @Test
    void create_nullCurrency_defaultsToCellCurrency_notHardcodedUsd() {
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        // KE cell: a merchant created without an explicit currency must inherit KES.
        // The allowlist unions the cell currency by construction (SupportedCurrencies),
        // which is exactly what keeps a KE cell working without extra config.
        MerchantService svc = new MerchantService(repo, mock(LoyaltyRuleRepository.class), PROPS,
                new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "KES"));
        ReflectionTestUtils.setField(svc, "cellCurrency", "KES");

        Dtos.MerchantRequest noCurrency = new Dtos.MerchantRequest(
                "Nairobi Cafe", "F&B", null, Merchant.BillingCycle.MONTHLY, null, null);
        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(), noCurrency);

        assertThat(resp.currency()).isEqualTo("KES");
    }

    @Test
    void create_explicitCurrency_winsOverCellDefault() {
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);
        ReflectionTestUtils.setField(svc, "cellCurrency", "KES");

        Dtos.MerchantRequest usd = new Dtos.MerchantRequest(
                "USD Merchant", "F&B", "USD", Merchant.BillingCycle.MONTHLY, null, null);
        Dtos.MerchantResponse resp = svc.create(UUID.randomUUID(), usd);

        assertThat(resp.currency()).isEqualTo("USD");
    }

    // --- Duplicate-name guard (per tenant, case-insensitive) ------------------

    @Test
    void create_firstMerchantWithName_succeeds() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UUID tenantId = UUID.randomUUID();
        when(repo.existsByTenantIdAndNameIgnoreCase(tenantId, "Cafe A")).thenReturn(false);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        Dtos.MerchantResponse resp = svc.create(tenantId, req(null, null));

        assertThat(resp.name()).isEqualTo("Cafe A");
        verify(repo).save(any(Merchant.class));
    }

    @Test
    void create_duplicateNameDifferentCase_throwsConflict() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UUID tenantId = UUID.randomUUID();
        // The IgnoreCase finder is what the DB would answer for a name that
        // already exists in any casing; simulate the hit.
        when(repo.existsByTenantIdAndNameIgnoreCase(tenantId, "cafe a")).thenReturn(true);
        MerchantService svc = newService(repo);

        Dtos.MerchantRequest sameNameLowerCase = new Dtos.MerchantRequest(
                "cafe a", "F&B", "USD", Merchant.BillingCycle.MONTHLY, null, null);

        assertThatThrownBy(() -> svc.create(tenantId, sameNameLowerCase))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(le.getCode()).isEqualTo("MERCHANT_NAME_TAKEN");
                });
        verify(repo, never()).save(any(Merchant.class));
    }

    @Test
    void create_trimsNameBeforeDuplicateCheckAndPersist() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UUID tenantId = UUID.randomUUID();
        when(repo.existsByTenantIdAndNameIgnoreCase(tenantId, "Cafe A")).thenReturn(false);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        Dtos.MerchantRequest padded = new Dtos.MerchantRequest(
                "  Cafe A  ", "F&B", "USD", Merchant.BillingCycle.MONTHLY, null, null);
        Dtos.MerchantResponse resp = svc.create(tenantId, padded);

        // Existence is checked against the trimmed name, and the trimmed name is persisted.
        verify(repo).existsByTenantIdAndNameIgnoreCase(tenantId, "Cafe A");
        assertThat(resp.name()).isEqualTo("Cafe A");
    }

    // --- Zero fees are allowed (owner decision, 2026-10-20); the console warns ---

    @Test
    void create_withNoIssueFeeAnywhere_isAllowed() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.MerchantResponse resp = new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, null, null));

        assertThat(resp.name()).isEqualTo("Cafe A");
    }

    @Test
    void create_withZeroIssueFee_isAllowed() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        Dtos.FeeModel freeIssue = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);

        Dtos.MerchantResponse resp = new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, freeIssue, null));

        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0");
    }

    @Test
    void create_withZeroRedeemFee_isAllowed() {
        actAsInnBucks();
        // Billing only the issue side is a normal arrangement — never refused.
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        Dtos.FeeModel freeRedeem = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);

        Dtos.MerchantResponse resp = newService(repo).create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Cafe A", null, null, null, PRICED, freeRedeem));

        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0.25");
        assertThat(resp.feeRedeemed().fixed()).isEqualByComparingTo("0");
    }

    @Test
    void create_pricedByTheTenantStandard_isAllowed() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> {
            Merchant saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        LoyaltyRule global = new LoyaltyRule();
        global.setTransactionType(TransactionType.PURCHASE);
        global.setFeeIssuedType(Merchant.FeeType.PERCENTAGE);
        global.setFeeIssuedFixed(java.math.BigDecimal.ZERO);
        global.setFeeIssuedPercentage(new java.math.BigDecimal("2.5"));
        when(rules.findApplicable(any(), any(), eq(TransactionType.PURCHASE))).thenReturn(List.of(global));

        // Nothing on the merchant itself, but the tenant standard prices it.
        Dtos.MerchantResponse resp = new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, null, null));

        assertThat(resp.id()).isNotNull();
        assertThat(resp.feeWaived()).isFalse();
    }

    @Test
    void create_pricedByTheOverride_isAllowed_evenBeforeTheRuleIsFlushed() {
        actAsInnBucks();
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> {
            Merchant saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(rules.save(any(LoyaltyRule.class))).thenAnswer(inv -> inv.getArgument(0));
        // findApplicable deliberately returns nothing: the guard must see the
        // rule it just created without depending on a flush.
        Dtos.MerchantRuleOverride override = new Dtos.MerchantRuleOverride(
                null, null, null, null, null, null,
                new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE, java.math.BigDecimal.ZERO, new java.math.BigDecimal("1")),
                null);

        Dtos.MerchantResponse resp = new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(),
                        new Dtos.MerchantRequest("Cafe A", null, null, null, null, null, override));

        assertThat(resp.id()).isNotNull();
    }

    @Test
    void create_unpricedButWaived_isAllowedAndRecorded() {
        actAsInnBucks();
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.MerchantResponse resp = newService(repo).create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Pilot Partner", null, null, null, null, null, null,
                        true, "Pilot partner - free for the first quarter"));

        assertThat(resp.feeWaived()).isTrue();
        assertThat(resp.feeWaivedReason()).isEqualTo("Pilot partner - free for the first quarter");
    }

    @Test
    void create_waiverWithoutAReason_isRefused() {
        actAsInnBucks();
        // A waiver with no reason is indistinguishable from an oversight the
        // moment anyone reads the audit.
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> newService(repo).create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Pilot Partner", null, null, null, null, null, null, true, "   ")))
                .hasMessageContaining("waiveFeesReason is required");
    }

    // --- Fees are InnBucks' (V67): a merchant admin may not choose them ---

    @Test
    void feeAuthority_aMerchantAdminSendingAFee_isRefused_andNothingIsSaved() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);

        assertThatThrownBy(() -> new MerchantService(repo, rules, PROPS, CURRENCIES)
                .create(UUID.randomUUID(), req(PRICED, null)))
                .isInstanceOfSatisfying(LoyaltyException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("FEES_PLATFORM_ONLY");
                });
        verify(repo, never()).save(any());
        verify(rules, never()).save(any());
    }

    @Test
    void feeAuthority_isCheckedBeforeTheFeeIsValidated() {
        // A merchant admin learns only that fees are not theirs to send, never
        // which shape of fee would have been accepted.
        Dtos.FeeModel negative = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                new java.math.BigDecimal("-0.10"), java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> newService(mock(MerchantRepository.class)).create(UUID.randomUUID(),
                req(null, negative)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FEES_PLATFORM_ONLY"));
    }

    @Test
    void feeAuthority_theOnboardingDefault_isNotAChoice_andIsAccepted() {
        // A console form that always sends FIXED 0 on both sides keeps working:
        // that is how the record has always read "not configured".
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        Dtos.FeeModel fixedZero = new Dtos.FeeModel(Merchant.FeeType.FIXED, null, null);
        Dtos.FeeModel typeless = new Dtos.FeeModel(null, null, null);

        Dtos.MerchantResponse resp = newService(repo).create(UUID.randomUUID(), req(fixedZero, typeless));

        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0");
        assertThat(resp.organizationId()).isEqualTo(ORG);
    }

    @Test
    void feeAuthority_aMerchantAdminWaivingFees_isRefused() {
        assertThatThrownBy(() -> newService(mock(MerchantRepository.class)).create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Pilot Partner", null, null, null, null, null, null,
                        true, "We want it free")))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FEES_PLATFORM_ONLY"));
    }

    @Test
    void feeAuthority_anyFeeOnTheOverride_evenFixedZero_isRefused() {
        // On a rule, FIXED 0 is an explicit zero that beats the tenant standard.
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        Dtos.MerchantRuleOverride zeroOnTheRule = new Dtos.MerchantRuleOverride(
                null, null, null, null, null, null, null,
                new Dtos.FeeModel(Merchant.FeeType.FIXED, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));

        assertThatThrownBy(() -> new MerchantService(repo, rules, PROPS, CURRENCIES).create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Cafe A", null, null, null, null, null, zeroOnTheRule)))
                .isInstanceOfSatisfying(LoyaltyException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FEES_PLATFORM_ONLY"));
        verify(repo, never()).save(any());
        verify(rules, never()).save(any());
    }

    @Test
    void feeAuthority_anOverrideWithoutFees_isStillTheMerchantAdminsToSet() {
        // The earn rate, floor and validity stay the merchant admin's.
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(rules.save(any(LoyaltyRule.class))).thenAnswer(inv -> inv.getArgument(0));
        Dtos.MerchantRuleOverride earnOnly = new Dtos.MerchantRuleOverride(
                null, new java.math.BigDecimal("2"), null, null, "MAIN",
                new java.math.BigDecimal("5.00"), null, null, 30);

        new MerchantService(repo, rules, PROPS, CURRENCIES).create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Cafe A", null, null, null, null, null, earnOnly));

        ArgumentCaptor<LoyaltyRule> saved = ArgumentCaptor.forClass(LoyaltyRule.class);
        verify(rules).save(saved.capture());
        assertThat(saved.getValue().getPointsPerUnit()).isEqualByComparingTo("2");
        assertThat(saved.getValue().getVoucherValidityDays()).isEqualTo(30);
        assertThat(saved.getValue().getFeeIssuedType()).isNull();
    }

    @Test
    void feeAuthority_aFinanceRoleHoldingTheCode_maySendFees() {
        // loyalty-fees:manage is not wildcard-reserved: a finance role that is not
        // SUPER_ADMIN may hold it. (Shown on the service; the endpoint's role list
        // still decides who reaches merchant create at all.)
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "owner@rudo.co.zw", null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_ADMIN"),
                        new SimpleGrantedAuthority("loyalty-fees:manage")));
        auth.setDetails(new CallerDetails(null, null, null, UUID.randomUUID(), ORG));
        SecurityContextHolder.getContext().setAuthentication(auth);
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.MerchantResponse resp = newService(repo).create(UUID.randomUUID(), req(PRICED, null));

        assertThat(resp.feeIssued().fixed()).isEqualByComparingTo("0.25");
    }

    @Test
    void audit_separatesTheForgottenFromTheDeliberate() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        UUID tenantId = UUID.randomUUID();

        Merchant priced = merchant(UUID.randomUUID(), tenantId, "Priced");
        priced.setFeeIssuedType(Merchant.FeeType.FIXED);
        priced.setFeeIssuedFixed(new java.math.BigDecimal("0.25"));
        Merchant forgotten = merchant(UUID.randomUUID(), tenantId, "Forgotten");
        Merchant deliberate = merchant(UUID.randomUUID(), tenantId, "Pilot");
        deliberate.setFeeWaived(true);
        deliberate.setFeeWaivedReason("Free pilot");
        when(repo.findByTenantId(tenantId)).thenReturn(List.of(priced, forgotten, deliberate));
        when(rules.findByTenantId(tenantId)).thenReturn(List.of());

        Dtos.ZeroFeeAudit audit = new MerchantService(repo, rules, PROPS, CURRENCIES)
                .auditZeroFeeMerchants(tenantId);

        assertThat(audit.merchantsExamined()).isEqualTo(3);
        assertThat(audit.issuingForFree()).isEqualTo(2);
        assertThat(audit.waived()).isEqualTo(1);
        assertThat(audit.unwaived()).isEqualTo(1);   // the one to go and price
        assertThat(audit.merchants()).extracting(Dtos.ZeroFeeMerchant::name)
                .containsExactlyInAnyOrder("Forgotten", "Pilot");
    }
}
