package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.VoucherTemplateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class VoucherTemplateService {

    private final VoucherTemplateRepository templates;
    private final MerchantService merchants;

    public VoucherTemplateService(VoucherTemplateRepository templates, MerchantService merchants) {
        this.templates = templates;
        this.merchants = merchants;
    }

    public VoucherTemplate create(UUID tenantId, UUID merchantId, Dtos.VoucherTemplateRequest req) {
        if (merchantId != null) merchants.requireMerchant(tenantId, merchantId);
        if (req.valueType() == VoucherTemplate.ValueType.FREE_ITEM && (req.freeItemSku() == null || req.freeItemSku().isBlank())) {
            throw LoyaltyException.badRequest("MISSING_SKU", "FREE_ITEM vouchers require freeItemSku");
        }
        // Duplicate-name guard: template names are unique per (tenant, merchant),
        // case-insensitive. Trim first. A null merchantId is a tenant-wide template
        // whose name is only unique among other tenant-wide templates — the IsNull
        // finder keeps that scope separate from any merchant's namespace.
        String name = req.name() == null ? "" : req.name().trim();
        boolean nameTaken = merchantId == null
                ? templates.existsByTenantIdAndMerchantIdIsNullAndNameIgnoreCase(tenantId, name)
                : templates.existsByTenantIdAndMerchantIdAndNameIgnoreCase(tenantId, merchantId, name);
        if (nameTaken) {
            throw LoyaltyException.conflict("VOUCHER_TEMPLATE_NAME_TAKEN",
                    "A voucher template with that name already exists.");
        }
        // AMOUNT / PERCENT value-types no longer require a value here —
        // each issuance picks its own face value at IssueVoucherRequest time.
        VoucherTemplate t = new VoucherTemplate();
        t.setTenantId(tenantId);
        t.setMerchantId(merchantId);
        t.setName(name);
        t.setType(req.type());
        t.setValueType(req.valueType());
        // Inherit the merchant's currency when the template doesn't override it
        // (was a hardcoded "USD" entity default).
        t.setCurrency(req.currency() != null ? req.currency()
                : merchants.requireMerchant(tenantId, merchantId).getCurrency());
        t.setFreeItemSku(req.freeItemSku());
        t.setUsageLimit(req.usageLimit());
        t.setValidityDays(req.validityDays());
        t.setApplicableOutlets(req.applicableOutlets());
        return templates.save(t);
    }

    @Transactional(readOnly = true)
    public List<VoucherTemplate> list(UUID tenantId) {
        return templates.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Page<VoucherTemplate> list(UUID tenantId, Pageable pageable) {
        return templates.findByTenantId(tenantId, pageable);
    }

    public VoucherTemplate require(UUID tenantId, UUID templateId) {
        VoucherTemplate t = templates.findById(templateId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher template"));
        if (!t.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "template belongs to a different tenant");
        }
        if (!t.isActive()) {
            throw LoyaltyException.badRequest("TEMPLATE_INACTIVE", "template is inactive");
        }
        return t;
    }
}
