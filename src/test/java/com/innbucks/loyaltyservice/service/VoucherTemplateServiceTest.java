package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.VoucherTemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VoucherTemplateService}'s duplicate-name guard. Template
 * names are unique per (tenant, merchant), case-insensitive; a null merchantId
 * (tenant-wide template) has its own namespace via the IsNull finder.
 */
class VoucherTemplateServiceTest {

    private static VoucherTemplateService newService(VoucherTemplateRepository templates,
                                                     MerchantService merchants) {
        return new VoucherTemplateService(templates, merchants);
    }

    private static Dtos.VoucherTemplateRequest req(UUID merchantId, String name) {
        // currency is supplied so the tenant-wide (null-merchant) path never has to
        // call merchants.requireMerchant to inherit a currency.
        return new Dtos.VoucherTemplateRequest(merchantId, name,
                VoucherTemplate.VoucherType.SINGLE_USE, VoucherTemplate.ValueType.AMOUNT,
                "USD", null, 1, 30, null);
    }

    private static Merchant merchant(UUID tenantId, UUID merchantId) {
        Merchant m = new Merchant();
        m.setId(merchantId);
        m.setTenantId(tenantId);
        m.setCurrency("USD");
        return m;
    }

    // --- Merchant-scoped ------------------------------------------------------

    @Test
    void create_firstTemplateWithName_succeeds() {
        VoucherTemplateRepository templates = mock(VoucherTemplateRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
        when(templates.existsByTenantIdAndMerchantIdAndNameIgnoreCase(tenantId, merchantId, "$5 Off Coffee"))
                .thenReturn(false);
        when(templates.save(any(VoucherTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        VoucherTemplate t = newService(templates, merchants)
                .create(tenantId, merchantId, req(merchantId, "$5 Off Coffee"));

        assertThat(t.getName()).isEqualTo("$5 Off Coffee");
        verify(templates).save(any(VoucherTemplate.class));
    }

    @Test
    void create_duplicateNameDifferentCase_perMerchant_throwsConflict() {
        VoucherTemplateRepository templates = mock(VoucherTemplateRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(merchants.requireMerchant(tenantId, merchantId)).thenReturn(merchant(tenantId, merchantId));
        when(templates.existsByTenantIdAndMerchantIdAndNameIgnoreCase(tenantId, merchantId, "$5 off coffee"))
                .thenReturn(true);

        assertThatThrownBy(() -> newService(templates, merchants)
                .create(tenantId, merchantId, req(merchantId, "$5 off coffee")))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> {
                    LoyaltyException le = (LoyaltyException) e;
                    assertThat(le.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(le.getCode()).isEqualTo("VOUCHER_TEMPLATE_NAME_TAKEN");
                });
        verify(templates, never()).save(any(VoucherTemplate.class));
    }

    // --- Tenant-wide (null merchant) ------------------------------------------

    @Test
    void create_duplicateNameDifferentCase_tenantWide_throwsConflict() {
        VoucherTemplateRepository templates = mock(VoucherTemplateRepository.class);
        MerchantService merchants = mock(MerchantService.class);
        UUID tenantId = UUID.randomUUID();
        // Null merchant → the IsNull finder is the one consulted.
        when(templates.existsByTenantIdAndMerchantIdIsNullAndNameIgnoreCase(tenantId, "welcome gift"))
                .thenReturn(true);

        assertThatThrownBy(() -> newService(templates, merchants)
                .create(tenantId, null, req(null, "welcome gift")))
                .isInstanceOf(LoyaltyException.class)
                .satisfies(e -> assertThat(((LoyaltyException) e).getCode())
                        .isEqualTo("VOUCHER_TEMPLATE_NAME_TAKEN"));
        verify(templates, never()).save(any(VoucherTemplate.class));
        // The merchant-scoped finder must NOT be used for a tenant-wide template.
        verify(templates, never())
                .existsByTenantIdAndMerchantIdAndNameIgnoreCase(any(), any(), any());
    }
}
