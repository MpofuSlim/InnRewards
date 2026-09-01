package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RuleAdminService#createCampaign}'s duplicate-name guard.
 * Campaign names are unique per (tenant, merchant), case-insensitive; a null
 * merchantId (tenant-wide campaign) has its own namespace via the IsNull finder.
 *
 * <p>Since these paths now run object-level authorization, each test establishes
 * the caller role the scope legitimately requires: a merchant-scoped write is
 * confined by {@link MerchantAuthz} (mocked no-op here), a tenant-wide write
 * needs a tenant-level role.
 */
class RuleAdminServiceCampaignTest {

    private static final LoyaltyProperties PROPS =
            new LoyaltyProperties(null, null, null, null, null, null, null);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithRoles(String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone@example.com", null, authorities));
    }

    private static RuleAdminService newService(CampaignRepository campaigns, MerchantService merchants) {
        return new RuleAdminService(mock(LoyaltyRuleRepository.class), campaigns, merchants,
                mock(MerchantAuthz.class), PROPS);
    }

    private static Dtos.CampaignRequest req(UUID merchantId, String name) {
        Instant start = Instant.now();
        Instant end = start.plus(7, ChronoUnit.DAYS);
        return new Dtos.CampaignRequest(merchantId, name, new BigDecimal("2.0000"), null, start, end);
    }


    @Test
    void createCampaign_firstWithName_succeeds() {
        CampaignRepository campaigns = mock(CampaignRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(campaigns.existsByTenantIdAndMerchantIdAndNameIgnoreCase(tenantId, merchantId, "Weekend 2x Points"))
                .thenReturn(false);
        when(campaigns.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        Campaign c = newService(campaigns, merchants)
                .createCampaign(tenantId, merchantId, req(merchantId, "Weekend 2x Points"));

        assertThat(c.getName()).isEqualTo("Weekend 2x Points");
        verify(campaigns).save(any(Campaign.class));
    }

    @Test
    void createCampaign_duplicateNameDifferentCase_perMerchant_throwsConflict() {
        CampaignRepository campaigns = mock(CampaignRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(campaigns.existsByTenantIdAndMerchantIdAndNameIgnoreCase(tenantId, merchantId, "weekend 2x points"))
                .thenReturn(true);

        assertThatThrownBy(() -> newService(campaigns, merchants)
                .createCampaign(tenantId, merchantId, req(merchantId, "weekend 2x points")))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(le.getCode()).isEqualTo("CAMPAIGN_NAME_TAKEN");
                });
        verify(campaigns, never()).save(any(Campaign.class));
    }

    @Test
    void createCampaign_duplicateNameDifferentCase_tenantWide_throwsConflict() {
        // A tenant-wide campaign is the tenant standard, so the legitimate caller
        // is a tenant admin — authenticate as one, then assert the name guard.
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        CampaignRepository campaigns = mock(CampaignRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        when(campaigns.existsByTenantIdAndMerchantIdIsNullAndNameIgnoreCase(tenantId, "black friday"))
                .thenReturn(true);

        assertThatThrownBy(() -> newService(campaigns, merchants)
                .createCampaign(tenantId, null, req(null, "black friday")))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode())
                        .isEqualTo("CAMPAIGN_NAME_TAKEN"));
        verify(campaigns, never()).save(any(Campaign.class));
        verify(campaigns, never())
                .existsByTenantIdAndMerchantIdAndNameIgnoreCase(any(), any(), any());
    }

    @Test
    void createCampaign_tenantWide_byMerchantAdmin_isForbidden() {
        // The escalation the audit found: a MERCHANT_ADMIN (no tenant-level role)
        // omitting merchantId must NOT be able to write the tenant-wide campaign.
        authenticateWithRoles("ROLE_MERCHANT_ADMIN");
        CampaignRepository campaigns = mock(CampaignRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> newService(campaigns, merchants)
                .createCampaign(tenantId, null, req(null, "black friday")))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(le.getCode()).isEqualTo("GLOBAL_RULE_ROLE");
                });
        verify(campaigns, never()).save(any(Campaign.class));
    }

    @Test
    void createCampaign_aboveMultiplierCeiling_isRejected() {
        authenticateWithRoles("ROLE_TENANT_ADMIN");
        CampaignRepository campaigns = mock(CampaignRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        // default max multiplier is 100; 1000 breaches it
        Instant start = Instant.now();
        Dtos.CampaignRequest over = new Dtos.CampaignRequest(
                null, "Mega", new BigDecimal("1000"), null, start, start.plus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> newService(campaigns, merchants).createCampaign(tenantId, null, over))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode())
                        .isEqualTo("EARN_MULTIPLIER_TOO_HIGH"));
        verify(campaigns, never()).save(any(Campaign.class));
    }
}
