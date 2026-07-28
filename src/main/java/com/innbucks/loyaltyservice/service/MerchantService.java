package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class MerchantService {

    private final MerchantRepository merchants;
    private final UserServiceClient userServiceClient;
    // Onboarding may create the merchant's own rule from its loyaltyOverride
    // block. The repository (not RuleAdminService) is injected deliberately:
    // RuleAdminService depends on THIS bean, so the reverse edge would be a
    // circular reference. The mapping/validation is still shared, via
    // RuleAdminService.build.
    private final com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository rules;

    @org.springframework.beans.factory.annotation.Value("${innbucks.currency:USD}")
    private String cellCurrency;


    public MerchantService(MerchantRepository merchants, UserServiceClient userServiceClient,
                           com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository rules) {
        this.merchants = merchants;
        this.userServiceClient = userServiceClient;
        this.rules = rules;
    }

    public Dtos.MerchantResponse create(UUID tenantId, Dtos.MerchantRequest req) {
        // Duplicate-name guard: a tenant can't have two merchants with the same
        // name (case-insensitive). Trim first so " Cafe" and "Cafe" collide.
        String name = req.name() == null ? "" : req.name().trim();
        if (merchants.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw LoyaltyException.conflict("MERCHANT_NAME_TAKEN",
                    "A merchant with that name already exists.");
        }
        Merchant m = new Merchant();
        m.setTenantId(tenantId);
        // Strip any HTML from free-text fields before persisting (stored-XSS
        // hardening). The dedup check above runs on the raw trimmed name;
        // stripAll is a no-op on legitimate names so that guard is unchanged.
        m.setName(HtmlSanitizer.stripAll(name));
        m.setCategory(HtmlSanitizer.stripAll(req.category()));
        // Default to this cell's currency (ZW=USD, KE=KES) — was a hardcoded
        // "USD" entity default that mislabelled every KE merchant.
        m.setCurrency(req.currency() != null ? req.currency() : cellCurrency);
        if (req.billingCycle() != null) m.setBillingCycle(req.billingCycle());
        applyFeeIssued(m, req.feeIssued());
        applyFeeRedeemed(m, req.feeRedeemed());
        m.setAdminEmail(callerEmail());
        merchants.save(m);
        return toResponse(m, createOverrideRule(tenantId, m.getId(), req.loyaltyOverride()));
    }

    /**
     * Turn the onboarding {@code loyaltyOverride} block into the merchant's own
     * rule, so an operator sets the merchant's terms in ONE call instead of
     * onboarding it and then remembering to POST a rule.
     *
     * <p>The override lands in {@code loyalty_rules}, not on extra merchant
     * columns, so there stays exactly one home for rule config and the existing
     * merchant-beats-global precedence applies unchanged. Omitted fields stay
     * null = "inherit the tenant's global rule for this field".
     *
     * @return the new rule's id, or null when no override was supplied.
     */
    private UUID createOverrideRule(UUID tenantId, UUID merchantId, Dtos.MerchantRuleOverride o) {
        if (o == null) {
            return null;
        }
        Dtos.RuleRequest asRule = new Dtos.RuleRequest(
                merchantId,
                o.transactionType() == null ? TransactionType.PURCHASE : o.transactionType(),
                o.pointsPerUnit() == null ? BigDecimal.ONE : o.pointsPerUnit(),
                o.multiplier(),
                o.maxPointsPerTxn(),
                o.pocket(),
                null, null,                       // no start/end window at onboarding
                o.minTransactionAmount(),
                o.feeIssued(),
                o.feeRedeemed());
        // The merchant was just created in this transaction, so the
        // requireMerchant round-trip createRule would do is redundant here.
        return rules.save(RuleAdminService.build(tenantId, merchantId, asRule)).getId();
    }

    /**
     * Validate the caller's {@link Dtos.FeeModel} against the constraints of
     * its {@code type} and stamp it onto the entity. Null input leaves the
     * entity defaults (FIXED 0) — the merchant simply isn't billed.
     *
     * <p>Type vs. value invariants enforced here so the error message can name
     * the offending field; the DB check constraints are non-negative only.
     */
    private static void applyFeeIssued(Merchant m, Dtos.FeeModel f) {
        if (f == null || f.type() == null) return;
        validate(f, "feeIssued");
        m.setFeeIssuedType(f.type());
        m.setFeeIssuedFixed(nz(f.fixed()));
        m.setFeeIssuedPercentage(nz(f.percentage()));
    }

    private static void applyFeeRedeemed(Merchant m, Dtos.FeeModel f) {
        if (f == null || f.type() == null) return;
        validate(f, "feeRedeemed");
        m.setFeeRedeemedType(f.type());
        m.setFeeRedeemedFixed(nz(f.fixed()));
        m.setFeeRedeemedPercentage(nz(f.percentage()));
    }

    static void validate(Dtos.FeeModel f, String fieldName) {
        BigDecimal fixed = nz(f.fixed());
        BigDecimal pct   = nz(f.percentage());
        if (fixed.signum() < 0 || pct.signum() < 0) {
            throw LoyaltyException.badRequest("FEE_NEGATIVE",
                    fieldName + ": fixed and percentage must be >= 0");
        }
        switch (f.type()) {
            case FIXED -> {
                if (pct.signum() != 0) {
                    throw LoyaltyException.badRequest("FEE_FIXED_HAS_PERCENT",
                            fieldName + ": type=FIXED requires percentage to be null or 0");
                }
            }
            case PERCENTAGE -> {
                if (fixed.signum() != 0) {
                    throw LoyaltyException.badRequest("FEE_PERCENT_HAS_FIXED",
                            fieldName + ": type=PERCENTAGE requires fixed to be null or 0");
                }
                if (pct.signum() == 0) {
                    throw LoyaltyException.badRequest("FEE_PERCENT_ZERO",
                            fieldName + ": type=PERCENTAGE requires percentage > 0");
                }
            }
            case FIXED_PLUS_PERCENTAGE -> {
                if (fixed.signum() == 0 || pct.signum() == 0) {
                    throw LoyaltyException.badRequest("FEE_BOTH_REQUIRED",
                            fieldName + ": type=FIXED_PLUS_PERCENTAGE requires both fixed > 0 and percentage > 0");
                }
            }
        }
    }

    static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static String callerEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        String name = auth.getName();
        return name == null || name.isBlank() ? null : name;
    }

    @Transactional(readOnly = true)
    public List<Dtos.MerchantResponse> list(UUID tenantId) {
        return merchants.findByTenantId(tenantId).stream().map(MerchantService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.MerchantResponse> list(UUID tenantId, Pageable pageable) {
        return list(tenantId, pageable, false);
    }

    /**
     * Tenant-scoped merchant page. When {@code unassigned} is true, the result
     * is filtered to merchants that do NOT yet have any MERCHANT_ADMIN user
     * attached — what the FE shows a registering merchant admin so they can
     * pick a yet-unclaimed merchant to bind themselves to.
     *
     * <p>The exclusion list is fetched from user-service on demand
     * ({@code GET /users/internal/merchants/assigned}). If user-service is
     * unreachable, we let the exception bubble — silently falling back to "all
     * merchants" would show the FE merchants that already have admins and
     * defeat the picker's whole purpose.
     */
    @Transactional(readOnly = true)
    public Page<Dtos.MerchantResponse> list(UUID tenantId, Pageable pageable, boolean unassigned) {
        if (!unassigned) {
            return merchants.findByTenantId(tenantId, pageable).map(MerchantService::toResponse);
        }
        Set<UUID> assigned = userServiceClient.assignedMerchantIds();
        Page<Merchant> page = assigned.isEmpty()
                // Hibernate refuses to emit `IN ()`; the no-exclusion case is
                // semantically identical to the unfiltered listing.
                ? merchants.findByTenantId(tenantId, pageable)
                : merchants.findByTenantIdAndIdNotIn(tenantId, assigned, pageable);
        return page.map(MerchantService::toResponse);
    }

    public Merchant requireMerchant(UUID tenantId, UUID merchantId) {
        Merchant m = merchants.findById(merchantId)
                .orElseThrow(() -> LoyaltyException.notFound("merchant"));
        if (!m.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "merchant belongs to a different tenant");
        }
        return m;
    }

    public Dtos.MerchantResponse setActive(UUID tenantId, UUID merchantId, boolean active) {
        Merchant m = requireMerchant(tenantId, merchantId);
        m.setStatus(active ? Merchant.Status.ACTIVE : Merchant.Status.INACTIVE);
        return toResponse(m);
    }

    public static Dtos.MerchantResponse toResponse(Merchant m) {
        return toResponse(m, null);
    }

    /** {@code loyaltyRuleId} is only known on the onboarding path — see the DTO. */
    public static Dtos.MerchantResponse toResponse(Merchant m, UUID loyaltyRuleId) {
        return new Dtos.MerchantResponse(m.getId(), m.getTenantId(), m.getName(),
                m.getCategory(), m.getCurrency(), m.getBillingCycle(), m.getStatus(),
                new Dtos.FeeModel(m.getFeeIssuedType(),   m.getFeeIssuedFixed(),   m.getFeeIssuedPercentage()),
                new Dtos.FeeModel(m.getFeeRedeemedType(), m.getFeeRedeemedFixed(), m.getFeeRedeemedPercentage()),
                loyaltyRuleId);
    }
}
