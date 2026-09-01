package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
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

    /**
     * Roles that oversee an entire tenant — every merchant in it, plus the
     * tenant-wide GLOBAL rule. A caller outside this set is a single-merchant
     * principal (MERCHANT_ADMIN / SHOP_ADMIN) and is confined to the merchant it
     * administers.
     */
    private static final String[] TENANT_LEVEL_ROLES =
            {"ROLE_SUPER_ADMIN", "ROLE_PLATFORM_ADMIN", "ROLE_TENANT_ADMIN"};

    private final LoyaltyRuleRepository rules;
    private final CampaignRepository campaigns;
    private final MerchantService merchants;
    private final MerchantAuthz merchantAuthz;
    private final LoyaltyProperties props;

    public RuleAdminService(LoyaltyRuleRepository rules, CampaignRepository campaigns,
                            MerchantService merchants, MerchantAuthz merchantAuthz,
                            LoyaltyProperties props) {
        this.rules = rules;
        this.campaigns = campaigns;
        this.merchants = merchants;
        this.merchantAuthz = merchantAuthz;
        this.props = props;
    }

    public LoyaltyRule createRule(UUID tenantId, UUID merchantId, Dtos.RuleRequest req) {
        authorizeRuleScopeWrite(tenantId, merchantId);
        return rules.save(build(tenantId, merchantId, req,
                props.earnRate().maxPointsPerUnit(), props.earnRate().maxMultiplier()));
    }

    /**
     * Object-level authorization for a rule/campaign WRITE at a given scope —
     * the gate the audit found missing, which let any tenant MERCHANT_ADMIN
     * rewrite a sibling merchant's earn rate or the tenant-wide standard.
     *
     * <ul>
     *   <li><b>Global (merchantId == null)</b> — the tenant STANDARD every
     *       merchant inherits, so only a tenant-level role may write it. A
     *       MERCHANT_ADMIN reaches this branch by omitting merchantId (its token
     *       carries no merchant claim), which is exactly the escalation to
     *       refuse.</li>
     *   <li><b>Merchant-scoped</b> — a tenant-level role may act on any merchant
     *       in the tenant (existence + tenant checked); a single-merchant
     *       principal is confined by {@link MerchantAuthz} to the merchant it
     *       administers, so it cannot target a sibling.</li>
     * </ul>
     *
     * <p>Deliberately NOT delegated wholesale to
     * {@code requireCallerAdministersMerchant}: that exempts only SUPER_ADMIN, so
     * it would wrongly 403 a TENANT_ADMIN managing a merchant in their own
     * tenant. The tier here preserves tenant-level reach while closing the
     * MERCHANT_ADMIN cross-scope hole.
     */
    private void authorizeRuleScopeWrite(UUID tenantId, UUID merchantId) {
        if (merchantId == null) {
            if (!CallerDetails.hasAnyRole(TENANT_LEVEL_ROLES)) {
                throw LoyaltyException.forbidden("GLOBAL_RULE_ROLE",
                        "Only a tenant administrator may create or change the tenant-wide "
                                + "(global) rule or campaign. Specify a merchantId to configure your "
                                + "own merchant instead.");
            }
            return;
        }
        if (CallerDetails.hasAnyRole(TENANT_LEVEL_ROLES)) {
            merchants.requireMerchant(tenantId, merchantId); // existence + tenant scope only
        } else {
            merchantAuthz.requireCallerAdministersMerchant(tenantId, merchantId);
        }
    }

    /**
     * Map a {@link Dtos.RuleRequest} onto a new (unsaved) rule, applying every
     * default and validation the create endpoint applies.
     *
     * <p>Static and caller-agnostic so merchant onboarding can create a
     * merchant's rule from its {@code loyaltyOverride} block without depending
     * on this bean — {@code RuleAdminService} already depends on
     * {@code MerchantService}, so a bean-level edge back would be a cycle.
     * Callers are responsible for the merchant-exists check and the save.
     *
     * <p>The {@code maxPointsPerUnit}/{@code maxMultiplier} ceilings are passed
     * in (from {@link LoyaltyProperties.EarnRate}) rather than read here, so this
     * one mapper enforces the platform earn-rate bound on BOTH the create
     * endpoint and merchant onboarding — no rate escapes the cap by which door it
     * came through. A non-positive ceiling disables that bound.
     */
    static LoyaltyRule build(UUID tenantId, UUID merchantId, Dtos.RuleRequest req,
                             BigDecimal maxPointsPerUnit, BigDecimal maxMultiplier) {
        // Earn-rate bounds (defence-in-depth behind the DTO @Positive): the
        // platform carries the liability for every point issued, so a rate is
        // refused at write time if it is non-positive or breaches the ceiling —
        // the earliest point, before any transaction can mint against it.
        if (req.pointsPerUnit() == null || req.pointsPerUnit().signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_EARN_RATE", "pointsPerUnit must be greater than zero.");
        }
        if (req.multiplier() != null && req.multiplier().signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_EARN_RATE", "multiplier must be greater than zero when set.");
        }
        if (req.maxPointsPerTxn() != null && req.maxPointsPerTxn().signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_EARN_RATE", "maxPointsPerTxn must be greater than zero when set.");
        }
        if (maxPointsPerUnit != null && maxPointsPerUnit.signum() > 0
                && req.pointsPerUnit().compareTo(maxPointsPerUnit) > 0) {
            throw LoyaltyException.badRequest("EARN_RATE_TOO_HIGH",
                    "pointsPerUnit " + req.pointsPerUnit().toPlainString() + " exceeds the platform maximum of "
                            + maxPointsPerUnit.toPlainString() + ".");
        }
        if (maxMultiplier != null && maxMultiplier.signum() > 0
                && req.multiplier() != null && req.multiplier().compareTo(maxMultiplier) > 0) {
            throw LoyaltyException.badRequest("EARN_MULTIPLIER_TOO_HIGH",
                    "multiplier " + req.multiplier().toPlainString() + " exceeds the platform maximum of "
                            + maxMultiplier.toPlainString() + ".");
        }

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
        // V29: earning floor + rule-level fee schedules (tenant standard on a
        // global rule; per-merchant override on a merchant rule). Same
        // validation as merchant onboarding; null = not configured -> inherit.
        if (req.minTransactionAmount() != null && req.minTransactionAmount().signum() < 0) {
            throw com.innbucks.loyaltyservice.exception.LoyaltyException.badRequest(
                    "MIN_TXN_NEGATIVE", "minTransactionAmount must be >= 0");
        }
        r.setMinTransactionAmount(req.minTransactionAmount());
        if (req.feeIssued() != null && req.feeIssued().type() != null) {
            MerchantService.validate(req.feeIssued(), "feeIssued");
            // A voucher-issue fee that is explicitly zero gives the platform
            // away, whether it is written on the TENANT standard (a global rule,
            // where it makes every merchant free) or on one merchant's rule
            // (where it silently undoes the guard on merchant creation). Issuing
            // is the event we bill for, so zero is only ever legitimate as a
            // deliberate, recorded decision — merchants.fee_waived — not as a
            // number typed into a rule. Redemption is untouched: it may be zero
            // freely.
            if (isZero(req.feeIssued())) {
                throw LoyaltyException.badRequest("RULE_ZERO_ISSUE_FEE",
                        "A voucher-issue fee of zero would run this for free. Set a non-zero feeIssued, "
                                + "omit it to inherit the tenant standard, or onboard the merchant with "
                                + "waiveFees=true and a reason if it is deliberately unbilled.");
            }
            r.setFeeIssuedType(req.feeIssued().type());
            r.setFeeIssuedFixed(MerchantService.nz(req.feeIssued().fixed()));
            r.setFeeIssuedPercentage(MerchantService.nz(req.feeIssued().percentage()));
        }
        if (req.feeRedeemed() != null && req.feeRedeemed().type() != null) {
            MerchantService.validate(req.feeRedeemed(), "feeRedeemed");
            r.setFeeRedeemedType(req.feeRedeemed().type());
            r.setFeeRedeemedFixed(MerchantService.nz(req.feeRedeemed().fixed()));
            r.setFeeRedeemedPercentage(MerchantService.nz(req.feeRedeemed().percentage()));
        }
        return r;
    }

    /**
     * True when this fee schedule would bill nothing. {@code validate} already
     * rejects a PERCENTAGE of 0 and a FIXED_PLUS_PERCENTAGE missing a leg, so in
     * practice this catches FIXED 0 — but it is written against the values
     * rather than the type so a future fee mode cannot slip past.
     */
    private static boolean isZero(Dtos.FeeModel f) {
        return MerchantService.nz(f.fixed()).signum() == 0
                && MerchantService.nz(f.percentage()).signum() == 0;
    }

    @Transactional(readOnly = true)
    public List<LoyaltyRule> listRules(UUID tenantId) {
        return rules.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Page<LoyaltyRule> listRules(UUID tenantId, Pageable pageable) {
        return rules.findByTenantId(tenantId, pageable);
    }

    public LoyaltyRule deactivateRule(UUID tenantId, UUID ruleId) {
        LoyaltyRule r = rules.findById(ruleId).orElseThrow(() -> LoyaltyException.notFound("rule"));
        if (!r.getTenantId().equals(tenantId)) throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        // Same scope authorization as creation, keyed on the rule's OWN scope:
        // a global rule needs a tenant-level role; a merchant rule needs that
        // merchant's admin (or a tenant-level role). The previous check keyed on
        // the caller's merchant CLAIM, which is null for a MERCHANT_ADMIN — so a
        // MERCHANT_ADMIN fell through both guards and could deactivate any rule,
        // global included. Routing through the role-based check closes that.
        authorizeRuleScopeWrite(tenantId, r.getMerchantId());
        r.setActive(false);
        return r;
    }

    public Campaign createCampaign(UUID tenantId, UUID merchantId, Dtos.CampaignRequest req) {
        authorizeRuleScopeWrite(tenantId, merchantId);
        // Same platform ceiling as a rule's multiplier — a campaign multiplier
        // stacks on top of the rule rate into the same liability, so an
        // unbounded campaign is the same mint vector by another door.
        if (req.multiplier() == null || req.multiplier().signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_EARN_RATE", "multiplier must be greater than zero.");
        }
        BigDecimal maxMultiplier = props.earnRate().maxMultiplier();
        if (maxMultiplier != null && maxMultiplier.signum() > 0
                && req.multiplier().compareTo(maxMultiplier) > 0) {
            throw LoyaltyException.badRequest("EARN_MULTIPLIER_TOO_HIGH",
                    "multiplier " + req.multiplier().toPlainString() + " exceeds the platform maximum of "
                            + maxMultiplier.toPlainString() + ".");
        }
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
