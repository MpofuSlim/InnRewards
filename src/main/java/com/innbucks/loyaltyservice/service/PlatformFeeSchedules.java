package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FeeSchedule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.FeeScheduleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.FeesPermissions;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * InnBucks' voucher fee schedules (V67): a tenant's default and each merchant's
 * own, set only by holders of {@code loyalty-fees:manage} or SUPER_ADMIN
 * (owner decision, 2026-11-12). The controller gates the writes; this class
 * also checks, so no other caller can reach a write without the authority.
 *
 * <p>Every change appends a row ({@code fee_schedules} is append-only), so the
 * history is the table. Withdrawing a schedule appends a tombstone.
 *
 * <p>It is also the {@link PlatformFeeBookSource} every pricing caller reads
 * through: the invoice run, the reporting estimates and the zero-fee audit.
 */
@Service
@Transactional
public class PlatformFeeSchedules implements PlatformFeeBookSource {

    static final int HISTORY_LIMIT = 50;

    private final FeeScheduleRepository schedules;
    private final TenantRepository tenants;
    private final MerchantRepository merchants;
    private final LoyaltyRuleRepository rules;
    private final MerchantAuthz merchantAuthz;

    public PlatformFeeSchedules(FeeScheduleRepository schedules, TenantRepository tenants,
                                MerchantRepository merchants, LoyaltyRuleRepository rules,
                                MerchantAuthz merchantAuthz) {
        this.schedules = schedules;
        this.tenants = tenants;
        this.merchants = merchants;
        this.rules = rules;
        this.merchantAuthz = merchantAuthz;
    }

    // ---------------------------------------------------------------- pricing

    @Override
    @Transactional(readOnly = true)
    public PlatformFeeBook forTenants(Collection<UUID> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return PlatformFeeBook.EMPTY;
        }
        return PlatformFeeBook.of(schedules.findLatestForTenants(tenantIds));
    }

    // ----------------------------------------------------------------- writes

    public Dtos.TenantFees setTenantDefault(UUID tenantId, Dtos.FeeScheduleRequest req) {
        requireFeeAuthority();
        Tenant t = requireTenant(tenantId);
        schedules.save(live(tenantId, null, req));
        return tenantView(t);
    }

    public Dtos.TenantFees clearTenantDefault(UUID tenantId) {
        requireFeeAuthority();
        Tenant t = requireTenant(tenantId);
        requireLive(forTenant(tenantId).tenantSchedule(tenantId).isPresent(), "this tenant");
        schedules.save(tombstone(tenantId, null));
        return tenantView(t);
    }

    public Dtos.MerchantFees setMerchant(UUID merchantId, Dtos.FeeScheduleRequest req) {
        requireFeeAuthority();
        Merchant m = requireMerchant(merchantId);
        schedules.save(live(m.getTenantId(), m.getId(), req));
        return merchantView(m);
    }

    public Dtos.MerchantFees clearMerchant(UUID merchantId) {
        requireFeeAuthority();
        Merchant m = requireMerchant(merchantId);
        requireLive(forTenant(m.getTenantId()).merchantSchedule(m.getId()).isPresent(), "this merchant");
        schedules.save(tombstone(m.getTenantId(), m.getId()));
        return merchantView(m);
    }

    // ------------------------------------------------------------------ reads

    /** A tenant's default and its history. Platform staff only (the controller gates it). */
    @Transactional(readOnly = true)
    public Dtos.TenantFees tenantFees(UUID tenantId) {
        return tenantView(requireTenant(tenantId));
    }

    /**
     * What a merchant pays today, where each fee comes from, and its own
     * history. Platform staff read any merchant; a merchant or shop admin reads
     * a merchant it may read ({@link MerchantAuthz#requireCallerMayReadMerchant}),
     * and is never told which staff member set a fee.
     */
    @Transactional(readOnly = true)
    public Dtos.MerchantFees merchantFees(UUID merchantId) {
        Merchant m = requireMerchant(merchantId);
        if (!FeesPermissions.callerMaySetFees()) {
            merchantAuthz.requireCallerMayReadMerchant(m.getTenantId(), m.getId());
        }
        return merchantView(m);
    }

    // ---------------------------------------------------------------- helpers

    private Dtos.TenantFees tenantView(Tenant t) {
        PlatformFeeBook book = forTenant(t.getId());
        List<Dtos.FeeScheduleEntry> history = schedules
                .findByTenantIdAndMerchantIdIsNullOrderByCreatedAtDescIdDesc(t.getId(),
                        PageRequest.of(0, HISTORY_LIMIT))
                .stream().map(PlatformFeeSchedules::entry).toList();
        return new Dtos.TenantFees(t.getId(), t.getName(),
                book.tenantSchedule(t.getId()).map(PlatformFeeSchedules::entry).orElse(null), history);
    }

    private Dtos.MerchantFees merchantView(Merchant m) {
        PlatformFeeBook book = forTenant(m.getTenantId());
        EffectiveFees fees = EffectiveFees.resolve(m,
                rules.findApplicable(m.getTenantId(), m.getId(), TransactionType.PURCHASE), book, Instant.now());
        List<Dtos.FeeScheduleEntry> history = schedules
                .findByMerchantIdOrderByCreatedAtDescIdDesc(m.getId(), PageRequest.of(0, HISTORY_LIMIT))
                .stream().map(PlatformFeeSchedules::entry).toList();
        return new Dtos.MerchantFees(m.getId(), m.getTenantId(), m.getName(),
                model(fees.issued()), fees.issued().source().name(),
                model(fees.redeemed()), fees.redeemed().source().name(),
                book.merchantSchedule(m.getId()).map(PlatformFeeSchedules::entry).orElse(null),
                book.tenantSchedule(m.getTenantId()).map(PlatformFeeSchedules::entry).orElse(null),
                history);
    }

    static Dtos.FeeScheduleEntry entry(FeeSchedule s) {
        return new Dtos.FeeScheduleEntry(s.getId(), s.isTenantDefault() ? "TENANT" : "MERCHANT",
                s.getTenantId(), s.getMerchantId(), s.isCleared(),
                s.isCleared() ? null
                        : new Dtos.FeeModel(s.getFeeIssuedType(), s.getFeeIssuedFixed(), s.getFeeIssuedPercentage()),
                s.isCleared() ? null
                        : new Dtos.FeeModel(s.getFeeRedeemedType(), s.getFeeRedeemedFixed(),
                        s.getFeeRedeemedPercentage()),
                s.getNote(),
                FeesPermissions.callerSeesFeeAuthors() ? s.getSetBy() : null,
                s.getCreatedAt());
    }

    private static Dtos.FeeModel model(EffectiveFees.Side side) {
        return new Dtos.FeeModel(side.type(), side.fixed(), side.percentage());
    }

    private static FeeSchedule live(UUID tenantId, UUID merchantId, Dtos.FeeScheduleRequest req) {
        Dtos.FeeModel issued = requireSide(req.feeIssued(), "feeIssued");
        Dtos.FeeModel redeemed = requireSide(req.feeRedeemed(), "feeRedeemed");
        FeeSchedule s = stamped(tenantId, merchantId);
        s.setFeeIssuedType(issued.type());
        s.setFeeIssuedFixed(MerchantService.nz(issued.fixed()));
        s.setFeeIssuedPercentage(MerchantService.nz(issued.percentage()));
        s.setFeeRedeemedType(redeemed.type());
        s.setFeeRedeemedFixed(MerchantService.nz(redeemed.fixed()));
        s.setFeeRedeemedPercentage(MerchantService.nz(redeemed.percentage()));
        String note = req.note() == null ? null : HtmlSanitizer.stripAll(req.note().strip());
        s.setNote(note == null || note.isBlank() ? null : note);
        return s;
    }

    private static FeeSchedule tombstone(UUID tenantId, UUID merchantId) {
        FeeSchedule s = stamped(tenantId, merchantId);
        s.setCleared(true);
        return s;
    }

    private static FeeSchedule stamped(UUID tenantId, UUID merchantId) {
        FeeSchedule s = new FeeSchedule();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setMerchantId(merchantId);
        // Microseconds, as the timestamptz column stores it, so the row this
        // call returns reads the same as the row read back later.
        s.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        UUID userId = CallerDetails.currentUserId();
        String who = CallerDetails.currentEmail();
        s.setSetBy(who != null && !who.isBlank() ? who : userId != null ? userId.toString() : "unknown");
        s.setSetByUserId(userId);
        return s;
    }

    /** Same rules as every other fee model, plus a type and a percentage no higher than 100. */
    private static Dtos.FeeModel requireSide(Dtos.FeeModel f, String field) {
        if (f == null || f.type() == null) {
            throw LoyaltyException.badRequest("FEE_TYPE_REQUIRED", field + ": type is required");
        }
        MerchantService.validate(f, field);
        if (MerchantService.nz(f.percentage()).compareTo(new BigDecimal("100")) > 0) {
            throw LoyaltyException.badRequest("FEE_PERCENT_TOO_HIGH",
                    field + ": percentage must be 100 or less");
        }
        return f;
    }

    private static void requireFeeAuthority() {
        if (!FeesPermissions.callerMaySetFees()) {
            throw LoyaltyException.forbidden(FeesPermissions.REFUSED_CODE, FeesPermissions.REFUSED_MESSAGE);
        }
    }

    private static void requireLive(boolean live, String scope) {
        if (!live) {
            throw LoyaltyException.conflict("NO_FEE_SCHEDULE",
                    "InnBucks has not set fees for " + scope + ", so there is nothing to withdraw.");
        }
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> LoyaltyException.notFound("tenant"));
    }

    private Merchant requireMerchant(UUID merchantId) {
        return merchants.findById(merchantId).orElseThrow(() -> LoyaltyException.notFound("merchant"));
    }
}
