package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import org.junit.jupiter.api.Test;

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
 */
class RuleAdminServiceCampaignTest {

    private static RuleAdminService newService(CampaignRepository campaigns, MerchantService merchants) {
        return new RuleAdminService(mock(LoyaltyRuleRepository.class), campaigns, merchants);
    }

    private static Dtos.CampaignRequest req(UUID merchantId, String name) {
        Instant start = Instant.now();
        Instant end = start.plus(7, ChronoUnit.DAYS);
        return new Dtos.CampaignRequest(merchantId, name, new BigDecimal("2.0000"), null, start, end);
    }

    private static Merchant merchant(UUID tenantId, UUID merchantId) {
        Merchant m = new Merchant();
        m.setId(merchantId);
        m.setTenantId(tenantId);
        return m;
    }

    @Test
    void createCampaign_firstWithName_succeeds() {
        CampaignRepository campaigns = mock(CampaignRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
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
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
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
}
