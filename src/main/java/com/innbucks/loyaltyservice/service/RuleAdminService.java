package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RuleAdminService {

    private final LoyaltyRuleRepository rules;
    private final CampaignRepository campaigns;
    private final MerchantService merchants;

    public RuleAdminService(LoyaltyRuleRepository rules, CampaignRepository campaigns,
                            MerchantService merchants) {
        this.rules = rules;
        this.campaigns = campaigns;
        this.merchants = merchants;
    }

    public LoyaltyRule createRule(UUID tenantId, UUID merchantId, Dtos.RuleRequest req) {
        if (merchantId != null) merchants.requireMerchant(tenantId, merchantId);
        LoyaltyRule r = new LoyaltyRule();
        r.setTenantId(tenantId);
        r.setMerchantId(merchantId);
        r.setTransactionType(req.transactionType());
        r.setPointsPerUnit(req.pointsPerUnit());
        r.setMultiplier(req.multiplier() == null ? BigDecimal.ONE : req.multiplier());
        r.setMaxPointsPerTxn(req.maxPointsPerTxn());
        r.setPocket(req.pocket());
        r.setStartsAt(req.startsAt());
        r.setEndsAt(req.endsAt());
        return rules.save(r);
    }

    @Transactional(readOnly = true)
    public List<LoyaltyRule> listRules(UUID tenantId) {
        return rules.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Page<LoyaltyRule> listRules(UUID tenantId, Pageable pageable) {
        return rules.findByTenantId(tenantId, pageable);
    }

    public LoyaltyRule deactivateRule(UUID tenantId, UUID ruleId, UUID callerMerchantId) {
        LoyaltyRule r = rules.findById(ruleId).orElseThrow(() -> LoyaltyException.notFound("rule"));
        if (!r.getTenantId().equals(tenantId)) throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        // Global rules (merchantId=null) can only be deactivated by TENANT_ADMIN+ (callerMerchantId=null).
        // Merchant-specific rules can only be deactivated by that merchant's admin.
        if (r.getMerchantId() == null && callerMerchantId != null) {
            throw LoyaltyException.forbidden("GLOBAL_RULE", "only tenant admin can deactivate global rules");
        }
        if (r.getMerchantId() != null && callerMerchantId != null && !r.getMerchantId().equals(callerMerchantId)) {
            throw LoyaltyException.forbidden("WRONG_MERCHANT", "rule belongs to a different merchant");
        }
        r.setActive(false);
        return r;
    }

    public Campaign createCampaign(UUID tenantId, UUID merchantId, Dtos.CampaignRequest req) {
        if (merchantId != null) merchants.requireMerchant(tenantId, merchantId);
        if (req.endsAt().isBefore(req.startsAt())) {
            throw LoyaltyException.badRequest("BAD_DATES", "endsAt must be after startsAt");
        }
        // Duplicate-name guard: campaign names are unique per (tenant, merchant),
        // case-insensitive. Trim first. A null merchantId is a tenant-wide campaign
        // whose name is only unique among other tenant-wide campaigns — the IsNull
        // finder keeps that scope separate from any merchant's namespace.
        String name = req.name() == null ? "" : HtmlSanitizer.stripAll(req.name().trim());
        boolean nameTaken = merchantId == null
                ? campaigns.existsByTenantIdAndMerchantIdIsNullAndNameIgnoreCase(tenantId, name)
                : campaigns.existsByTenantIdAndMerchantIdAndNameIgnoreCase(tenantId, merchantId, name);
        if (nameTaken) {
            throw LoyaltyException.conflict("CAMPAIGN_NAME_TAKEN",
                    "A campaign with that name already exists.");
        }
        Campaign c = new Campaign();
        c.setTenantId(tenantId);
        c.setMerchantId(merchantId);
        c.setName(name);
        c.setMultiplier(req.multiplier());
        c.setTransactionType(req.transactionType());
        c.setStartsAt(req.startsAt());
        c.setEndsAt(req.endsAt());
        return campaigns.save(c);
    }

    @Transactional(readOnly = true)
    public List<Campaign> listCampaigns(UUID tenantId) {
        return campaigns.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Page<Campaign> listCampaigns(UUID tenantId, Pageable pageable) {
        return campaigns.findByTenantId(tenantId, pageable);
    }
}
