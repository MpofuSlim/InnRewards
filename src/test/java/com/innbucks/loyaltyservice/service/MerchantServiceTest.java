package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MerchantService#list(UUID, Pageable, boolean)}.
 *
 * <p>Pins how the unassigned filter routes between the repository's two
 * finders depending on what user-service returns, and that user-service
 * failures propagate (no silent fallback — see the service comment).
 */
class MerchantServiceTest {

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
    void list_defaultUnassignedFalse_skipsUserServiceAndReturnsAll() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(repo.findByTenantId(tenantId, page))
                .thenReturn(new PageImpl<>(List.of(merchant(UUID.randomUUID(), tenantId, "A"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient, mock(LoyaltyRuleRepository.class)).list(tenantId, page);

        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByTenantId(tenantId, page);
        verifyNoInteractions(userClient);
    }

    @Test
    void list_unassignedTrue_emptyExclusionSet_fallsThroughToFindByTenantId() {
        // Hibernate refuses to emit `IN ()`; when nobody has an admin yet,
        // the unassigned page IS the unfiltered page.
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds()).thenReturn(Set.of());
        when(repo.findByTenantId(tenantId, page))
                .thenReturn(new PageImpl<>(List.of(merchant(UUID.randomUUID(), tenantId, "Solo"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient, mock(LoyaltyRuleRepository.class)).list(tenantId, page, true);

        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByTenantId(tenantId, page);
        verify(repo, never()).findByTenantIdAndIdNotIn(any(), any(), any());
    }

    @Test
    void list_unassignedTrue_nonEmptyExclusion_callsNotInFinderWithThatSet() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID claimed = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds()).thenReturn(Set.of(claimed));
        when(repo.findByTenantIdAndIdNotIn(eq(tenantId), eq(Set.of(claimed)), eq(page)))
                .thenReturn(new PageImpl<>(List.of(merchant(free, tenantId, "Up for grabs"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient, mock(LoyaltyRuleRepository.class)).list(tenantId, page, true);

        assertThat(result.getContent()).extracting(Dtos.MerchantResponse::id).containsExactly(free);
        verify(repo).findByTenantIdAndIdNotIn(tenantId, Set.of(claimed), page);
        verify(repo, never()).findByTenantId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void list_unassignedTrue_userServiceDown_propagatesIllegalStateException() {
        // Silent fallback to "all merchants" would show the picker
        // already-claimed merchants and defeat the whole feature, so the
        // service lets the exception bubble for the controller to map to 503.
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds())
                .thenThrow(new IllegalStateException("user-service unavailable"));

        assertThatThrownBy(() -> new MerchantService(repo, userClient, mock(LoyaltyRuleRepository.class)).list(tenantId, page, true))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repo);
    }

    private static MerchantService newService(MerchantRepository repo) {
        return new MerchantService(repo, mock(UserServiceClient.class), mock(LoyaltyRuleRepository.class));
    }

    private static final Dtos.FeeModel PRICED =
            new Dtos.FeeModel(Merchant.FeeType.FIXED, new java.math.BigDecimal("0.25"), java.math.BigDecimal.ZERO);

    private static Dtos.MerchantRequest req(Dtos.FeeModel issued, Dtos.FeeModel redeemed) {
        // Default to a priced issue side: creation is refused outright when the
        // effective issue fee is zero, so a fee-less request is no longer a
        // neutral fixture.
        return new Dtos.MerchantRequest("Cafe A", "F&B", "USD",
                Merchant.BillingCycle.MONTHLY, issued == null ? PRICED : issued, redeemed);
    }

    @Test
    void create_acceptsFixedPlusPercentage() {
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

        Dtos.MerchantResponse resp = new MerchantService(repo, mock(UserServiceClient.class), rules)
                .create(UUID.randomUUID(), req(null, null));

        // No override -> no rule is written. The repository IS read, because the
        // zero-fee guard has to resolve what this merchant would actually be
        // billed, so assert on the write rather than on no interaction at all.
        verify(rules, never()).save(any());
        assertThat(resp.loyaltyRuleId()).isNull();
    }

    @Test
    void create_withOverride_createsTheMerchantsOwnRule() {
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

        Dtos.MerchantResponse resp = new MerchantService(repo, mock(UserServiceClient.class), rules)
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
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        Dtos.MerchantRuleOverride bad = new Dtos.MerchantRuleOverride(
                null, null, null, null, null, null,
                new Dtos.FeeModel(Merchant.FeeType.FIXED,
                        new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5")),
                null);

        assertThatThrownBy(() -> new MerchantService(repo, mock(UserServiceClient.class), rules)
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

        assertThatThrownBy(() -> new MerchantService(repo, mock(UserServiceClient.class), rules)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, null, null, bad)))
                .hasMessageContaining("minTransactionAmount");
        verify(rules, never()).save(any());
    }

    @Test
    void create_rejectsFixedWithNonZeroPercentage() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("FIXED")
                .hasMessageContaining("percentage");
    }

    @Test
    void create_rejectsPercentageWithNonZeroFixed() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE,
                new java.math.BigDecimal("0.30"), new java.math.BigDecimal("2.5"));

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("PERCENTAGE")
                .hasMessageContaining("fixed");
    }

    @Test
    void create_rejectsPercentageWithZeroPercentage() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.PERCENTAGE,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("percentage > 0");
    }

    @Test
    void create_rejectsFixedPlusPercentageMissingALeg() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED_PLUS_PERCENTAGE,
                new java.math.BigDecimal("0.30"), java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining("FIXED_PLUS_PERCENTAGE");
    }

    @Test
    void create_rejectsNegativeValues() {
        MerchantService svc = newService(mock(MerchantRepository.class));
        Dtos.FeeModel bad = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                new java.math.BigDecimal("-0.10"), java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), req(bad, null)))
                .hasMessageContaining(">= 0");
    }

    @Test
    void create_nullFeeModels_defaultEntityToFixedZero() {
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        MerchantService svc = newService(repo);

        // Waived, because an unpriced merchant is now refused outright — the
        // entity defaults this pins are unaffected by that guard.
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
        MerchantService svc = newService(repo);
        // KE cell: a merchant created without an explicit currency must inherit KES.
        ReflectionTestUtils.setField(svc, "cellCurrency", "KES");

        Dtos.MerchantRequest noCurrency = new Dtos.MerchantRequest(
                "Nairobi Cafe", "F&B", null, Merchant.BillingCycle.MONTHLY, PRICED, null);
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
                "USD Merchant", "F&B", "USD", Merchant.BillingCycle.MONTHLY, PRICED, null);
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
                "cafe a", "F&B", "USD", Merchant.BillingCycle.MONTHLY, PRICED, null);

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
                "  Cafe A  ", "F&B", "USD", Merchant.BillingCycle.MONTHLY, PRICED, null);
        Dtos.MerchantResponse resp = svc.create(tenantId, padded);

        // Existence is checked against the trimmed name, and the trimmed name is persisted.
        verify(repo).existsByTenantIdAndNameIgnoreCase(tenantId, "Cafe A");
        assertThat(resp.name()).isEqualTo("Cafe A");
    }

    // --- No free merchants: the issue fee must be priced or explicitly waived ---

    @Test
    void create_withNoIssueFeeAnywhere_isRefused() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        // No record fee, no override, no global rule -> the platform would run
        // this merchant for free forever.
        assertThatThrownBy(() -> new MerchantService(repo, mock(UserServiceClient.class), rules)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, null, null)))
                .hasMessageContaining("billed nothing for issuing");
    }

    @Test
    void create_withZeroIssueFee_isRefused() {
        MerchantRepository repo = mock(MerchantRepository.class);
        LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        Dtos.FeeModel freeIssue = new Dtos.FeeModel(Merchant.FeeType.FIXED,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);

        // An explicit FIXED 0 is still zero — spelling it out is not a waiver.
        assertThatThrownBy(() -> new MerchantService(repo, mock(UserServiceClient.class), rules)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, freeIssue, null)))
                .hasMessageContaining("billed nothing for issuing");
    }

    @Test
    void create_withZeroRedeemFee_isAllowed() {
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
        Dtos.MerchantResponse resp = new MerchantService(repo, mock(UserServiceClient.class), rules)
                .create(UUID.randomUUID(), new Dtos.MerchantRequest("Cafe A", null, null, null, null, null));

        assertThat(resp.id()).isNotNull();
        assertThat(resp.feeWaived()).isFalse();
    }

    @Test
    void create_pricedByTheOverride_isAllowed_evenBeforeTheRuleIsFlushed() {
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

        Dtos.MerchantResponse resp = new MerchantService(repo, mock(UserServiceClient.class), rules)
                .create(UUID.randomUUID(),
                        new Dtos.MerchantRequest("Cafe A", null, null, null, null, null, override));

        assertThat(resp.id()).isNotNull();
    }

    @Test
    void create_unpricedButWaived_isAllowedAndRecorded() {
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
        // A waiver with no reason is indistinguishable from an oversight the
        // moment anyone reads the audit.
        MerchantRepository repo = mock(MerchantRepository.class);
        when(repo.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> newService(repo).create(UUID.randomUUID(),
                new Dtos.MerchantRequest("Pilot Partner", null, null, null, null, null, null, true, "   ")))
                .hasMessageContaining("waiveFeesReason is required");
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

        Dtos.ZeroFeeAudit audit = new MerchantService(repo, mock(UserServiceClient.class), rules)
                .auditZeroFeeMerchants(tenantId);

        assertThat(audit.merchantsExamined()).isEqualTo(3);
        assertThat(audit.issuingForFree()).isEqualTo(2);
        assertThat(audit.waived()).isEqualTo(1);
        assertThat(audit.unwaived()).isEqualTo(1);   // the one to go and price
        assertThat(audit.merchants()).extracting(Dtos.ZeroFeeMerchant::name)
                .containsExactlyInAnyOrder("Forgotten", "Pilot");
    }
}
