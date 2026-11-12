package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.FeeSchedule;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fee-resolution precedence. InnBucks' own schedules come first (V67):
 * the merchant's, else its tenant's default. Then the V29 legacy levels:
 * merchant rule beats the merchant record, which beats the tenant-wide
 * (global) rule, which beats "no fee".
 *
 * <p>Pure JUnit — {@link EffectiveFees} takes the rules as a list, so no
 * repository or spring context is involved.
 */
class EffectiveFeesTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    /** A merchant left at the onboarding defaults: FIXED 0/0 on both sides. */
    private static Merchant unconfiguredMerchant() {
        Merchant m = new Merchant();
        m.setId(MERCHANT_ID);
        m.setTenantId(UUID.randomUUID());
        m.setName("Speke Avenue");
        return m;
    }

    private static LoyaltyRule rule(UUID merchantId) {
        LoyaltyRule r = new LoyaltyRule();
        r.setId(UUID.randomUUID());
        r.setMerchantId(merchantId);
        r.setTransactionType(TransactionType.PURCHASE);
        r.setPointsPerUnit(BigDecimal.ONE);
        r.setMultiplier(BigDecimal.ONE);
        return r;
    }

    private static LoyaltyRule withIssuedFee(LoyaltyRule r, Merchant.FeeType type,
                                             String fixed, String pct) {
        r.setFeeIssuedType(type);
        r.setFeeIssuedFixed(new BigDecimal(fixed));
        r.setFeeIssuedPercentage(new BigDecimal(pct));
        return r;
    }

    private static LoyaltyRule withRedeemedFee(LoyaltyRule r, Merchant.FeeType type,
                                               String fixed, String pct) {
        r.setFeeRedeemedType(type);
        r.setFeeRedeemedFixed(new BigDecimal(fixed));
        r.setFeeRedeemedPercentage(new BigDecimal(pct));
        return r;
    }

    private static Voucher voucher(String faceValue) {
        Voucher v = new Voucher();
        v.setValue(new BigDecimal(faceValue));
        return v;
    }

    @Test
    void nothingConfiguredAnywhereMeansNoFee() {
        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(),
                List.of(rule(null), rule(MERCHANT_ID)), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0");
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("0");
    }

    @Test
    void unconfiguredMerchantInheritsTheGlobalRuleStandard() {
        // The tenant standard: $0.30 + 2.5% on issue, flat $0.15 on redeem.
        LoyaltyRule global = withRedeemedFee(
                withIssuedFee(rule(null), Merchant.FeeType.FIXED_PLUS_PERCENTAGE, "0.30", "2.5"),
                Merchant.FeeType.FIXED, "0.15", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(global), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("2.80");   // 0.30 + 2.50
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("0.15");
    }

    @Test
    void merchantRuleOverridesTheGlobalStandard() {
        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");
        LoyaltyRule mine = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.PERCENTAGE, "0", "1");

        // findApplicable returns merchant-specific rules first.
        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(mine, global), NOW);

        assertThat(fees.feeForIssued(voucher("200"))).isEqualByComparingTo("2");   // 1%, not 5%
    }

    @Test
    void merchantRuleWithFixedZeroIsTheExplicitOptOut() {
        // The escape hatch documented on /loyalty/rules: a merchant that should
        // pay nothing while a tenant standard exists sets FIXED 0 on its own rule.
        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");
        LoyaltyRule mine = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "0", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(mine, global), NOW);

        assertThat(fees.feeForIssued(voucher("200"))).isEqualByComparingTo("0");
    }

    @Test
    void explicitMerchantRecordFeeStillBeatsTheGlobalRule() {
        // Merchants onboarded with their own negotiated fee (pre-V29 behaviour)
        // keep it — the global rule is only the default for the un-negotiated.
        Merchant negotiated = unconfiguredMerchant();
        negotiated.setFeeIssuedType(Merchant.FeeType.FIXED);
        negotiated.setFeeIssuedFixed(new BigDecimal("1.00"));

        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");

        EffectiveFees fees = EffectiveFees.resolve(negotiated, List.of(global), NOW);

        assertThat(fees.feeForIssued(voucher("200"))).isEqualByComparingTo("1.00");
    }

    @Test
    void sidesResolveIndependently() {
        // Merchant overrides only the issue side; the redeem side keeps falling
        // through to the tenant standard.
        LoyaltyRule global = withRedeemedFee(
                withIssuedFee(rule(null), Merchant.FeeType.FIXED, "0.50", "0"),
                Merchant.FeeType.FIXED, "0.25", "0");
        LoyaltyRule mine = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "0.10", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(mine, global), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.10");
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("0.25");
    }

    @Test
    void inactiveOrOutOfWindowRulesAreIgnored() {
        LoyaltyRule expired = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "9.99", "0");
        expired.setEndsAt(NOW.minus(1, ChronoUnit.DAYS));

        LoyaltyRule notYetLive = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "8.88", "0");
        notYetLive.setStartsAt(NOW.plus(1, ChronoUnit.DAYS));

        LoyaltyRule deactivated = withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "7.77", "0");
        deactivated.setActive(false);

        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.FIXED, "0.20", "0");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(),
                List.of(expired, notYetLive, deactivated, global), NOW);

        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.20");
    }

    @Test
    void nullVoucherFaceValueDoesNotBlowUpPercentageFees() {
        // Pre-V7 vouchers carry no face value; under-billing them is the safe
        // direction (same guard MerchantFeeCalculator has).
        LoyaltyRule global = withIssuedFee(rule(null), Merchant.FeeType.PERCENTAGE, "0", "5");

        EffectiveFees fees = EffectiveFees.resolve(unconfiguredMerchant(), List.of(global), NOW);

        assertThat(fees.feeForIssued(new Voucher())).isEqualByComparingTo("0");
    }

    // ── applicable(): the in-memory twin of LoyaltyRuleRepository.findApplicable

    @Test
    void applicableFiltersByTypeAndOwnershipMerchantFirst() {
        LoyaltyRule mine = rule(MERCHANT_ID);
        mine.setCreatedAt(NOW.minus(2, ChronoUnit.DAYS));
        LoyaltyRule global = rule(null);
        global.setCreatedAt(NOW.minus(1, ChronoUnit.DAYS));
        LoyaltyRule someoneElses = rule(UUID.randomUUID());
        LoyaltyRule otherType = rule(MERCHANT_ID);
        otherType.setTransactionType(TransactionType.REFUND);
        LoyaltyRule inactive = rule(MERCHANT_ID);
        inactive.setActive(false);

        List<LoyaltyRule> applicable = EffectiveFees.applicable(
                List.of(global, someoneElses, otherType, inactive, mine),
                MERCHANT_ID, TransactionType.PURCHASE);

        // Merchant-specific first even though the global rule is newer — same
        // ordering the repository query produces.
        assertThat(applicable).containsExactly(mine, global);
    }

    @Test
    void applicableToleratesANullRuleList() {
        assertThat(EffectiveFees.applicable(null, MERCHANT_ID, TransactionType.PURCHASE)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Voucher validity resolution (V45) — merchant rule → global → default
    // ------------------------------------------------------------------

    private static LoyaltyRule withValidity(LoyaltyRule r, Integer days) {
        r.setVoucherValidityDays(days);
        return r;
    }

    @Test
    void validity_defaultsWhenNoRuleSetsOne() {
        assertThat(EffectiveFees.resolveVoucherValidityDays(
                List.of(rule(MERCHANT_ID), rule(null)), NOW, 365)).isEqualTo(365);
    }

    @Test
    void validity_globalRuleIsTheTenantStandard() {
        assertThat(EffectiveFees.resolveVoucherValidityDays(
                List.of(rule(MERCHANT_ID), withValidity(rule(null), 60)), NOW, 365)).isEqualTo(60);
    }

    @Test
    void validity_merchantRuleOverridesTheGlobalStandard() {
        assertThat(EffectiveFees.resolveVoucherValidityDays(
                List.of(withValidity(rule(MERCHANT_ID), 14), withValidity(rule(null), 60)),
                NOW, 365)).isEqualTo(14);
    }

    @Test
    void validity_anInactiveOrExpiredMerchantRuleIsIgnored() {
        LoyaltyRule stale = withValidity(rule(MERCHANT_ID), 14);
        stale.setEndsAt(NOW.minusSeconds(60));
        assertThat(EffectiveFees.resolveVoucherValidityDays(
                List.of(stale, withValidity(rule(null), 60)), NOW, 365)).isEqualTo(60);
    }

    @Test
    void validity_toleratesANullRuleList() {
        assertThat(EffectiveFees.resolveVoucherValidityDays(null, NOW, 365)).isEqualTo(365);
    }

    // ── InnBucks' schedules (V67) ───────────────────────────────────────────

    private static FeeSchedule schedule(UUID tenantId, UUID merchantId, String issuedFixed, String redeemedPct,
                                        Instant at) {
        FeeSchedule s = new FeeSchedule();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setMerchantId(merchantId);
        s.setFeeIssuedType(Merchant.FeeType.FIXED);
        s.setFeeIssuedFixed(new BigDecimal(issuedFixed));
        s.setFeeIssuedPercentage(BigDecimal.ZERO);
        s.setFeeRedeemedType(Merchant.FeeType.PERCENTAGE);
        s.setFeeRedeemedFixed(BigDecimal.ZERO);
        s.setFeeRedeemedPercentage(new BigDecimal(redeemedPct));
        s.setSetBy("finance@innbucks.co.zw");
        s.setCreatedAt(at);
        return s;
    }

    private static FeeSchedule withdrawn(UUID tenantId, UUID merchantId, Instant at) {
        FeeSchedule s = new FeeSchedule();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setMerchantId(merchantId);
        s.setCleared(true);
        s.setSetBy("finance@innbucks.co.zw");
        s.setCreatedAt(at);
        return s;
    }

    /** A merchant that set everything the legacy way: its own rule AND an explicit record fee. */
    private static Merchant merchantWithLegacyFees(List<LoyaltyRule> rulesOut) {
        Merchant m = unconfiguredMerchant();
        m.setFeeIssuedType(Merchant.FeeType.FIXED);
        m.setFeeIssuedFixed(new BigDecimal("0.05"));
        m.setFeeIssuedPercentage(BigDecimal.ZERO);
        rulesOut.add(withRedeemedFee(withIssuedFee(rule(MERCHANT_ID), Merchant.FeeType.FIXED, "0.01", "0"),
                Merchant.FeeType.FIXED, "0.01", "0"));
        rulesOut.add(withIssuedFee(rule(null), Merchant.FeeType.FIXED, "0.02", "0"));
        return m;
    }

    @Test
    void schedules_theMerchantsOwnScheduleBeatsEverything() {
        java.util.List<LoyaltyRule> rules = new java.util.ArrayList<>();
        Merchant m = merchantWithLegacyFees(rules);
        PlatformFeeBook book = PlatformFeeBook.of(List.of(
                schedule(m.getTenantId(), null, "0.40", "2", NOW.minusSeconds(120)),
                schedule(m.getTenantId(), m.getId(), "0.30", "1", NOW.minusSeconds(60))));

        EffectiveFees fees = EffectiveFees.resolve(m, rules, book, NOW);

        assertThat(fees.issued().source()).isEqualTo(EffectiveFees.Source.MERCHANT_SCHEDULE);
        assertThat(fees.redeemed().source()).isEqualTo(EffectiveFees.Source.MERCHANT_SCHEDULE);
        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.30");
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("1.00");
    }

    @Test
    void schedules_theTenantDefaultBeatsEveryLegacyLevel() {
        java.util.List<LoyaltyRule> rules = new java.util.ArrayList<>();
        Merchant m = merchantWithLegacyFees(rules);
        PlatformFeeBook book = PlatformFeeBook.of(List.of(
                schedule(m.getTenantId(), null, "0.40", "2", NOW.minusSeconds(60))));

        EffectiveFees fees = EffectiveFees.resolve(m, rules, book, NOW);

        // Both sides come from the one schedule, even the side the merchant's own
        // rule set: a merchant cannot keep a fee InnBucks has replaced.
        assertThat(fees.issued().source()).isEqualTo(EffectiveFees.Source.TENANT_SCHEDULE);
        assertThat(fees.redeemed().source()).isEqualTo(EffectiveFees.Source.TENANT_SCHEDULE);
        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.40");
        assertThat(fees.feeForRedeemed(voucher("100"))).isEqualByComparingTo("2.00");
    }

    @Test
    void schedules_anotherTenantsDefaultOrAnotherMerchantsScheduleDoesNotApply() {
        java.util.List<LoyaltyRule> rules = new java.util.ArrayList<>();
        Merchant m = merchantWithLegacyFees(rules);
        PlatformFeeBook book = PlatformFeeBook.of(List.of(
                schedule(UUID.randomUUID(), null, "0.40", "2", NOW.minusSeconds(60)),
                schedule(m.getTenantId(), UUID.randomUUID(), "0.30", "1", NOW.minusSeconds(60))));

        EffectiveFees fees = EffectiveFees.resolve(m, rules, book, NOW);

        assertThat(fees.issued().source()).isEqualTo(EffectiveFees.Source.MERCHANT_RULE);
        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.01");
    }

    @Test
    void schedules_aWithdrawnMerchantScheduleFallsBackToTheTenantDefault() {
        Merchant m = unconfiguredMerchant();
        PlatformFeeBook book = PlatformFeeBook.of(List.of(
                schedule(m.getTenantId(), null, "0.40", "2", NOW.minusSeconds(300)),
                schedule(m.getTenantId(), m.getId(), "0.30", "1", NOW.minusSeconds(200)),
                withdrawn(m.getTenantId(), m.getId(), NOW.minusSeconds(100))));

        EffectiveFees fees = EffectiveFees.resolve(m, List.of(), book, NOW);

        assertThat(fees.issued().source()).isEqualTo(EffectiveFees.Source.TENANT_SCHEDULE);
        assertThat(fees.feeForIssued(voucher("100"))).isEqualByComparingTo("0.40");
    }

    @Test
    void schedules_withNothingLiveFromInnBucks_theLegacyLevelsApplyExactlyAsBefore() {
        java.util.List<LoyaltyRule> rules = new java.util.ArrayList<>();
        Merchant m = merchantWithLegacyFees(rules);
        PlatformFeeBook book = PlatformFeeBook.of(List.of(
                schedule(m.getTenantId(), null, "0.40", "2", NOW.minusSeconds(200)),
                withdrawn(m.getTenantId(), null, NOW.minusSeconds(100))));

        EffectiveFees withBook = EffectiveFees.resolve(m, rules, book, NOW);
        EffectiveFees legacy = EffectiveFees.resolve(m, rules, NOW);

        assertThat(withBook.issued()).isEqualTo(legacy.issued());
        assertThat(withBook.redeemed()).isEqualTo(legacy.redeemed());
        assertThat(EffectiveFees.resolve(m, rules, PlatformFeeBook.EMPTY, NOW).issued()).isEqualTo(legacy.issued());
        assertThat(EffectiveFees.resolve(m, rules, null, NOW).issued()).isEqualTo(legacy.issued());
    }

    @Test
    void schedules_aZeroScheduleIsInnBucksChoosingNoFee_notAFallThrough() {
        Merchant m = unconfiguredMerchant();
        java.util.List<LoyaltyRule> rules = List.of(withIssuedFee(rule(null), Merchant.FeeType.FIXED, "0.02", "0"));
        PlatformFeeBook book = PlatformFeeBook.of(List.of(
                schedule(m.getTenantId(), m.getId(), "0", "0", NOW.minusSeconds(60))));

        EffectiveFees fees = EffectiveFees.resolve(m, rules, book, NOW);

        assertThat(fees.issued().source()).isEqualTo(EffectiveFees.Source.MERCHANT_SCHEDULE);
        assertThat(fees.issuesForFree()).isTrue();
    }

    @Test
    void legacySourcesAreNamed() {
        java.util.List<LoyaltyRule> rules = new java.util.ArrayList<>();
        Merchant m = merchantWithLegacyFees(rules);
        assertThat(EffectiveFees.resolve(m, rules, NOW).issued().source())
                .isEqualTo(EffectiveFees.Source.MERCHANT_RULE);
        assertThat(EffectiveFees.resolve(m, List.of(rules.get(1)), NOW).issued().source())
                .isEqualTo(EffectiveFees.Source.MERCHANT_RECORD);
        assertThat(EffectiveFees.resolve(unconfiguredMerchant(), List.of(rules.get(1)), NOW).issued().source())
                .isEqualTo(EffectiveFees.Source.TENANT_RULE);
        assertThat(EffectiveFees.resolve(unconfiguredMerchant(), List.of(), NOW).issued().source())
                .isEqualTo(EffectiveFees.Source.NONE);
    }
}
