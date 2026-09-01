package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The earn-side authorization + rate-bound contract (audit item 2): who may
 * write which rule scope, and the platform ceiling on how rich a rate can be.
 *
 * <p>Pure Mockito + a hand-set SecurityContext — no Docker/Spring (CLAUDE.md).
 * The authz decision reads {@code SecurityContextHolder}, so each test
 * authenticates the caller role under test; {@link MerchantAuthz} is mocked so a
 * single-merchant principal's confinement is asserted by whether that collaborator
 * is consulted, not by re-testing its internals (it has its own tests).
 */
class RuleAdminServiceAuthzTest {

    private static final LoyaltyProperties PROPS =
            new LoyaltyProperties(null, null, null, null, null, null, null);

    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final MerchantService merchants = mock(MerchantService.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final RuleAdminService service =
            new RuleAdminService(rules, campaigns, merchants, merchantAuthz, PROPS);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithRoles(String... roles) {
        var auth = new UsernamePasswordAuthenticationToken("someone@example.com", null,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static Dtos.RuleRequest rule(String pointsPerUnit, BigDecimal multiplier) {
        return new Dtos.RuleRequest(MERCHANT, TransactionType.PURCHASE, new BigDecimal(pointsPerUnit),
                multiplier, null, "MAIN", null, null, null, null, null);
    }

    private static Dtos.RuleRequest globalRule(String pointsPerUnit) {
        return new Dtos.RuleRequest(null, TransactionType.PURCHASE, new BigDecimal(pointsPerUnit),
                null, null, "MAIN", null, null, null, null, null);
    }

    // ---------- object-level authz ----------

    @Test
    @DisplayName("MERCHANT_ADMIN targeting a merchant goes through MerchantAuthz confinement")
    void merchantAdminIsConfinedByMerchantAuthz() {
        authenticateWithRoles("ROLE_MERCHANT_ADMIN");
        when(rules.save(any(LoyaltyRule.class))).thenAnswer(i -> i.getArgument(0));

        service.createRule(TENANT, MERCHANT, rule("1", null));

        // The ownership gate was consulted — a sibling merchant would throw inside it.
        verify(merchantAuthz).requireCallerAdministersMerchant(TENANT, MERCHANT);
        // And it did NOT get the tenant-level bypass (which would skip confinement).
        verify(merchants, never()).requireMerchant(any(), any());
    }

    @Test
    @DisplayName("a MerchantAuthz denial propagates — a sibling merchant's rule is refused")
    void merchantAdminSiblingDenied() {
        authenticateWithRoles("ROLE_MERCHANT_ADMIN");
        doThrow(LoyaltyException.forbidden("NOT_MERCHANT_OWNER", "You can only act on merchants you administer."))
                .when(merchantAuthz).requireCallerAdministersMerchant(TENANT, MERCHANT);

        assertThatThrownBy(() -> service.createRule(TENANT, MERCHANT, rule("1", null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("NOT_MERCHANT_OWNER"));
        verify(rules, never()).save(any());
    }

    @Test
    @DisplayName("TENANT_ADMIN may write any merchant's rule (tenant-level reach, no confinement)")
    void tenantAdminHasTenantWideReach() {
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        when(rules.save(any(LoyaltyRule.class))).thenAnswer(i -> i.getArgument(0));

        service.createRule(TENANT, MERCHANT, rule("1", null));

        verify(merchants).requireMerchant(TENANT, MERCHANT);
        verify(merchantAuthz, never()).requireCallerAdministersMerchant(any(), any());
    }

    @Test
    @DisplayName("a MERCHANT_ADMIN may NOT create the tenant-wide global rule")
    void merchantAdminCannotWriteGlobalRule() {
        authenticateWithRoles("ROLE_MERCHANT_ADMIN");

        assertThatThrownBy(() -> service.createRule(TENANT, null, globalRule("1")))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(le.getCode()).isEqualTo("GLOBAL_RULE_ROLE");
                });
        verify(rules, never()).save(any());
    }

    @Test
    @DisplayName("a TENANT_ADMIN may create the global rule")
    void tenantAdminCanWriteGlobalRule() {
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        when(rules.save(any(LoyaltyRule.class))).thenAnswer(i -> i.getArgument(0));

        LoyaltyRule saved = service.createRule(TENANT, null, globalRule("1"));
        assertThat(saved.getMerchantId()).isNull();
        verify(rules).save(any());
    }

    @Test
    @DisplayName("deactivating a global rule as a MERCHANT_ADMIN is refused (the null-claim bypass is closed)")
    void merchantAdminCannotDeactivateGlobalRule() {
        authenticateWithRoles("ROLE_MERCHANT_ADMIN");
        LoyaltyRule global = new LoyaltyRule();
        global.setTenantId(TENANT);
        global.setMerchantId(null);
        UUID ruleId = UUID.randomUUID();
        when(rules.findById(ruleId)).thenReturn(java.util.Optional.of(global));

        assertThatThrownBy(() -> service.deactivateRule(TENANT, ruleId))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("GLOBAL_RULE_ROLE"));
        assertThat(global.isActive()).isTrue(); // untouched
    }

    // ---------- rate bounds ----------

    @Test
    @DisplayName("an earn rate above the platform ceiling is refused (the unbounded-mint fix)")
    void rateAboveCeilingRejected() {
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        // default max points-per-unit is 1000; 1_000_000 is the audit's attack value
        assertThatThrownBy(() -> service.createRule(TENANT, MERCHANT, rule("1000000", null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("EARN_RATE_TOO_HIGH"));
        verify(rules, never()).save(any());
    }

    @Test
    @DisplayName("a multiplier above the platform ceiling is refused")
    void multiplierAboveCeilingRejected() {
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        // default max multiplier is 100
        assertThatThrownBy(() -> service.createRule(TENANT, MERCHANT, rule("1", new BigDecimal("1000"))))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("EARN_MULTIPLIER_TOO_HIGH"));
        verify(rules, never()).save(any());
    }

    @Test
    @DisplayName("a non-positive rate is refused at the service layer too (not only bean validation)")
    void nonPositiveRateRejected() {
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        assertThatThrownBy(() -> service.createRule(TENANT, MERCHANT, rule("0", null)))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode()).isEqualTo("BAD_EARN_RATE"));
    }

    @Test
    @DisplayName("a legitimate rate within the ceiling is accepted")
    void legitimateRateAccepted() {
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        when(merchants.requireMerchant(TENANT, MERCHANT)).thenReturn(new Merchant());
        when(rules.save(any(LoyaltyRule.class))).thenAnswer(i -> i.getArgument(0));

        LoyaltyRule saved = service.createRule(TENANT, MERCHANT, rule("2", new BigDecimal("3")));
        assertThat(saved.getPointsPerUnit()).isEqualByComparingTo("2");
        assertThat(saved.getMultiplier()).isEqualByComparingTo("3");
    }
}
