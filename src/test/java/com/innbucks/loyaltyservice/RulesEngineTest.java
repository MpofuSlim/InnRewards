package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.service.RulesEngine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RulesEngineTest {

    @Test
    void merchantOverrideBeatsGlobal() {
        LoyaltyRuleRepository rules = Mockito.mock(LoyaltyRuleRepository.class);
        CampaignRepository campaigns = Mockito.mock(CampaignRepository.class);

        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        LoyaltyRule global = new LoyaltyRule();
        global.setTenantId(tenantId);
        global.setMerchantId(null);
        global.setTransactionType(TransactionType.PURCHASE);
        global.setPointsPerUnit(BigDecimal.ONE);
        global.setMultiplier(BigDecimal.ONE);

        LoyaltyRule override = new LoyaltyRule();
        override.setTenantId(tenantId);
        override.setMerchantId(merchantId);
        override.setTransactionType(TransactionType.PURCHASE);
        override.setPointsPerUnit(new BigDecimal("2"));
        override.setMultiplier(BigDecimal.ONE);

        // Repository is expected to return overrides first per its ORDER BY.
        Mockito.when(rules.findApplicable(tenantId, merchantId, TransactionType.PURCHASE))
                .thenReturn(List.of(override, global));
        Mockito.when(campaigns.findActive(Mockito.eq(tenantId), Mockito.eq(merchantId),
                Mockito.eq(TransactionType.PURCHASE), Mockito.any()))
                .thenReturn(List.of());

        RulesEngine engine = new RulesEngine(rules, campaigns);
        var eval = engine.evaluate(tenantId, merchantId, TransactionType.PURCHASE, new BigDecimal("100"));
        assertThat(eval.points()).isEqualByComparingTo("200");
    }

    @Test
    void campaignMultiplierStacks() {
        LoyaltyRuleRepository rules = Mockito.mock(LoyaltyRuleRepository.class);
        CampaignRepository campaigns = Mockito.mock(CampaignRepository.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        LoyaltyRule rule = new LoyaltyRule();
        rule.setTenantId(tenantId);
        rule.setTransactionType(TransactionType.PURCHASE);
        rule.setPointsPerUnit(BigDecimal.ONE);
        rule.setMultiplier(BigDecimal.ONE);

        Campaign c = new Campaign();
        c.setMultiplier(new BigDecimal("2"));

        Mockito.when(rules.findApplicable(tenantId, merchantId, TransactionType.PURCHASE))
                .thenReturn(List.of(rule));
        Mockito.when(campaigns.findActive(Mockito.eq(tenantId), Mockito.eq(merchantId),
                Mockito.eq(TransactionType.PURCHASE), Mockito.any()))
                .thenReturn(List.of(c));

        RulesEngine engine = new RulesEngine(rules, campaigns);
        var eval = engine.evaluate(tenantId, merchantId, TransactionType.PURCHASE, new BigDecimal("50"));
        assertThat(eval.points()).isEqualByComparingTo("100");
    }

    @Test
    void roundsPointsDownToWholeNumber() {
        // A $1.30 purchase at 1 point/unit must yield 1 point, not 1.3.
        // A $2.80 purchase must yield 2, not 3 (HALF_UP would have rounded
        // 2.8 up to 3 — the bug this test pins fixed).
        LoyaltyRuleRepository rules = Mockito.mock(LoyaltyRuleRepository.class);
        CampaignRepository campaigns = Mockito.mock(CampaignRepository.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        LoyaltyRule rule = new LoyaltyRule();
        rule.setTenantId(tenantId);
        rule.setTransactionType(TransactionType.PURCHASE);
        rule.setPointsPerUnit(BigDecimal.ONE);
        rule.setMultiplier(BigDecimal.ONE);

        Mockito.when(rules.findApplicable(Mockito.any(), Mockito.any(), Mockito.eq(TransactionType.PURCHASE)))
                .thenReturn(List.of(rule));
        Mockito.when(campaigns.findActive(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());

        RulesEngine engine = new RulesEngine(rules, campaigns);

        assertThat(engine.evaluate(tenantId, merchantId, TransactionType.PURCHASE, new BigDecimal("1.30")).points())
                .isEqualByComparingTo("1");
        assertThat(engine.evaluate(tenantId, merchantId, TransactionType.PURCHASE, new BigDecimal("2.80")).points())
                .isEqualByComparingTo("2");
        // Sub-unit transactions floor to zero — no fractional-points trickle.
        assertThat(engine.evaluate(tenantId, merchantId, TransactionType.PURCHASE, new BigDecimal("0.99")).points())
                .isEqualByComparingTo("0");
    }

    @Test
    void cardPaymentEarnsPointsLikePurchase() {
        // CARD_PAYMENT is a new TransactionType (V19); it must route through
        // the rules engine the same way PURCHASE does. RulesEngine excludes
        // REFUND / ADJUSTMENT from earning — pin that CARD_PAYMENT isn't
        // accidentally added to the exclusion side of the same `if`.
        LoyaltyRuleRepository rules = Mockito.mock(LoyaltyRuleRepository.class);
        CampaignRepository campaigns = Mockito.mock(CampaignRepository.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        LoyaltyRule rule = new LoyaltyRule();
        rule.setTenantId(tenantId);
        rule.setTransactionType(TransactionType.CARD_PAYMENT);
        rule.setPointsPerUnit(BigDecimal.ONE);
        rule.setMultiplier(BigDecimal.ONE);

        Mockito.when(rules.findApplicable(tenantId, merchantId, TransactionType.CARD_PAYMENT))
                .thenReturn(List.of(rule));
        Mockito.when(campaigns.findActive(Mockito.eq(tenantId), Mockito.eq(merchantId),
                Mockito.eq(TransactionType.CARD_PAYMENT), Mockito.any()))
                .thenReturn(List.of());

        RulesEngine engine = new RulesEngine(rules, campaigns);
        var eval = engine.evaluate(tenantId, merchantId, TransactionType.CARD_PAYMENT, new BigDecimal("50"));
        assertThat(eval.points()).isEqualByComparingTo("50");
    }

    @Test
    void capRespectsMaxPerTransaction() {
        LoyaltyRuleRepository rules = Mockito.mock(LoyaltyRuleRepository.class);
        CampaignRepository campaigns = Mockito.mock(CampaignRepository.class);
        UUID tenantId = UUID.randomUUID();

        LoyaltyRule rule = new LoyaltyRule();
        rule.setTenantId(tenantId);
        rule.setTransactionType(TransactionType.PURCHASE);
        rule.setPointsPerUnit(BigDecimal.ONE);
        rule.setMultiplier(BigDecimal.ONE);
        rule.setMaxPointsPerTxn(new BigDecimal("10"));

        Mockito.when(rules.findApplicable(Mockito.any(), Mockito.any(), Mockito.eq(TransactionType.PURCHASE)))
                .thenReturn(List.of(rule));
        Mockito.when(campaigns.findActive(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(List.of());

        RulesEngine engine = new RulesEngine(rules, campaigns);
        var eval = engine.evaluate(tenantId, UUID.randomUUID(), TransactionType.PURCHASE, new BigDecimal("9999"));
        assertThat(eval.points()).isEqualByComparingTo("10");
    }
}
