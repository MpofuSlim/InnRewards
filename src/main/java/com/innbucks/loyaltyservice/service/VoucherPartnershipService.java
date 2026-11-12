package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.VoucherPartnerProperties;
import com.innbucks.loyaltyservice.dto.PartnershipDtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.VoucherPartnership;
import com.innbucks.loyaltyservice.entity.VoucherPartnershipMerchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnershipMerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnershipRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tenant-to-tenant voucher partnerships (V68, owner decisions 2026-11-12): a
 * tenant invites another by its code; the other side accepts or declines;
 * either side can end it; each side puts in its own merchants. A SUPER_ADMIN
 * acts for any tenant (MWOS and Bola Bet have no login), and alone can suspend
 * or reinstate. A {@code generic} partner voucher then also works at the other
 * side's merchants ({@link #refusedTargets} decides which).
 *
 * <p>Ending never strands a voucher already sold: its list was fixed at issue
 * and stays good until it expires. A suspension stops use at once
 * ({@link #suspendedBetween}), because InnBucks suspends for a reason.
 *
 * <p><b>The staging switch</b> ({@code LOYALTY_PARTNERSHIP_AUTO_ACCEPT}) makes an
 * invitation ACTIVE at once, with every active merchant of the invited tenant,
 * marked {@code autoAccepted}. It works only while voucher money is simulated:
 * with real funding it is ignored and the boot logs an ERROR.
 */
@Service
@Transactional
public class VoucherPartnershipService {

    private static final Logger log = LoggerFactory.getLogger(VoucherPartnershipService.class);

    static final Set<VoucherPartnership.Status> LIVE =
            EnumSet.of(VoucherPartnership.Status.INVITED, VoucherPartnership.Status.ACTIVE,
                    VoucherPartnership.Status.SUSPENDED);

    private final VoucherPartnershipRepository partnerships;
    private final VoucherPartnershipMerchantRepository members;
    private final TenantRepository tenants;
    private final MerchantRepository merchants;
    private final MerchantAuthz merchantAuthz;
    private final boolean autoAccept;

    public VoucherPartnershipService(VoucherPartnershipRepository partnerships,
                                     VoucherPartnershipMerchantRepository members, TenantRepository tenants,
                                     MerchantRepository merchants, MerchantAuthz merchantAuthz,
                                     VoucherPartnerProperties partnerProps,
                                     @Value("${loyalty.partnership.auto-accept:false}") boolean autoAcceptRequested) {
        this.partnerships = partnerships;
        this.members = members;
        this.tenants = tenants;
        this.merchants = merchants;
        this.merchantAuthz = merchantAuthz;
        boolean simulated = partnerProps != null && partnerProps.simulatedFunding();
        this.autoAccept = autoAcceptRequested && simulated;
        if (autoAcceptRequested && !simulated) {
            log.error("LOYALTY_PARTNERSHIP_AUTO_ACCEPT is set but voucher funding is not SIMULATED: ignored. "
                    + "Partnerships wait for the invited tenant to accept.");
        } else if (autoAccept) {
            log.warn("LOYALTY_PARTNERSHIP_AUTO_ACCEPT is ON (staging only): every invitation becomes active "
                    + "without the other tenant accepting.");
        }
    }

    // ================================================================== reads

    @Transactional(readOnly = true)
    public List<PartnershipDtos.PartnershipView> list(UUID tenantId) {
        return views(partnerships.findInvolving(tenantId));
    }

    @Transactional(readOnly = true)
    public List<PartnershipDtos.PartnershipView> listAll() {
        return views(partnerships.findAllByOrderByInvitedAtDescIdDesc());
    }

    // ================================================================= writes

    public PartnershipDtos.PartnershipView invite(UUID tenantId, PartnershipDtos.InviteRequest req) {
        Tenant partner = tenantByCode(req.partnerTenantCode());
        if (partner.getId().equals(tenantId)) {
            throw LoyaltyException.badRequest("PARTNERSHIP_SELF", "A tenant can't partner with itself.");
        }
        List<Merchant> own = requireOwnMerchants(tenantId, req.merchantIds());
        if (!partnerships.findBetween(tenantId, partner.getId(), LIVE).isEmpty()) {
            throw exists();
        }
        Instant now = now();
        String actor = actor();
        VoucherPartnership p = new VoucherPartnership();
        p.setId(UUID.randomUUID());
        p.setInviterTenantId(tenantId);
        p.setInviteeTenantId(partner.getId());
        p.setInvitedBy(actor);
        p.setInvitedOnBehalf(onBehalf());
        p.setInvitedAt(now);
        p.setStatus(VoucherPartnership.Status.INVITED);
        if (autoAccept) {
            p.setStatus(VoucherPartnership.Status.ACTIVE);
            p.setAutoAccepted(true);
            p.setDecidedBy("auto-accept (staging)");
            p.setDecidedOnBehalf(true);
            p.setDecidedAt(now);
        }
        try {
            partnerships.saveAndFlush(p);
        } catch (DataIntegrityViolationException race) {
            // Another invitation between the same two tenants committed first.
            throw exists();
        }
        addSide(p, tenantId, own, actor, now);
        if (autoAccept) {
            List<Merchant> theirs = merchants.findByTenantId(partner.getId()).stream()
                    .filter(m -> m.getStatus() == Merchant.Status.ACTIVE).toList();
            addSide(p, partner.getId(), theirs, "auto-accept (staging)", now);
        }
        log.info("Voucher partnership {} {} tenantId={} -> tenantId={} onBehalf={}", p.getId(),
                autoAccept ? "auto-accepted" : "invited", tenantId, partner.getId(), p.isInvitedOnBehalf());
        return view(p);
    }

    public PartnershipDtos.PartnershipView accept(UUID tenantId, UUID id, PartnershipDtos.MerchantsRequest req) {
        VoucherPartnership p = lockInvolving(tenantId, id);
        requireInvitee(p, tenantId);
        List<Merchant> own = requireOwnMerchants(tenantId, req.merchantIds());
        Instant now = now();
        p.setStatus(VoucherPartnership.Status.ACTIVE);
        decide(p, now);
        replaceSide(p, tenantId, own, now);
        log.info("Voucher partnership {} accepted by tenantId={}", id, tenantId);
        return view(p);
    }

    public PartnershipDtos.PartnershipView decline(UUID tenantId, UUID id) {
        VoucherPartnership p = lockInvolving(tenantId, id);
        requireInvitee(p, tenantId);
        p.setStatus(VoucherPartnership.Status.DECLINED);
        decide(p, now());
        log.info("Voucher partnership {} declined by tenantId={}", id, tenantId);
        return view(p);
    }

    public PartnershipDtos.PartnershipView setMerchants(UUID tenantId, UUID id, PartnershipDtos.MerchantsRequest req) {
        VoucherPartnership p = lockInvolving(tenantId, id);
        requireLive(p);
        if (p.getStatus() == VoucherPartnership.Status.INVITED && p.getInviteeTenantId().equals(tenantId)) {
            // The invited side puts its merchants in by accepting.
            throw LoyaltyException.conflict("PARTNERSHIP_NOT_ANSWERED",
                    "Accept the invitation to choose your merchants.");
        }
        replaceSide(p, tenantId, requireOwnMerchants(tenantId, req.merchantIds()), now());
        return view(p);
    }

    public PartnershipDtos.PartnershipView end(UUID tenantId, UUID id) {
        VoucherPartnership p = lockInvolving(tenantId, id);
        requireLive(p);
        p.setStatus(VoucherPartnership.Status.ENDED);
        p.setEndedBy(actor());
        p.setEndedOnBehalf(onBehalf());
        p.setEndedAt(now());
        log.info("Voucher partnership {} ended by tenantId={}", id, tenantId);
        return view(p);
    }

    /** SUPER_ADMIN only (the controller gates it): stops new vouchers and every use at once. */
    public PartnershipDtos.PartnershipView suspend(UUID id, PartnershipDtos.SuspendRequest req) {
        VoucherPartnership p = partnerships.lockById(id).orElseThrow(() -> LoyaltyException.notFound("partnership"));
        if (p.getStatus() != VoucherPartnership.Status.ACTIVE) {
            throw LoyaltyException.conflict("PARTNERSHIP_NOT_ACTIVE", "Only an active partnership can be suspended.");
        }
        p.setStatus(VoucherPartnership.Status.SUSPENDED);
        p.setSuspendedBy(actor());
        p.setSuspendedAt(now());
        p.setSuspensionReason(HtmlSanitizer.stripAll(req.reason().strip()));
        log.info("Voucher partnership {} suspended", id);
        return view(p);
    }

    public PartnershipDtos.PartnershipView reinstate(UUID id) {
        VoucherPartnership p = partnerships.lockById(id).orElseThrow(() -> LoyaltyException.notFound("partnership"));
        if (p.getStatus() != VoucherPartnership.Status.SUSPENDED) {
            throw LoyaltyException.conflict("PARTNERSHIP_NOT_SUSPENDED", "This partnership is not suspended.");
        }
        p.setStatus(VoucherPartnership.Status.ACTIVE);
        p.setSuspendedBy(null);
        p.setSuspendedAt(null);
        p.setSuspensionReason(null);
        log.info("Voucher partnership {} reinstated", id);
        return view(p);
    }

    // ============================================================ the voucher side

    /**
     * The targets a voucher of {@code issuer} may NOT name: a merchant of another
     * tenant with no ACTIVE partnership in which both the issuer and that
     * merchant take part. A merchant of the issuer's own tenant is always
     * allowed: the tenant is the brand (owner decision, 2026-10-08).
     */
    @Transactional(readOnly = true)
    public List<Merchant> refusedTargets(Merchant issuer, Collection<Merchant> targets) {
        Map<UUID, List<Merchant>> byTenant = new HashMap<>();
        for (Merchant t : targets) {
            if (!t.getTenantId().equals(issuer.getTenantId())) {
                byTenant.computeIfAbsent(t.getTenantId(), k -> new ArrayList<>()).add(t);
            }
        }
        List<Merchant> refused = new ArrayList<>();
        for (Map.Entry<UUID, List<Merchant>> e : byTenant.entrySet()) {
            Set<UUID> in = partnerships.findBetween(issuer.getTenantId(), e.getKey(),
                            EnumSet.of(VoucherPartnership.Status.ACTIVE)).stream()
                    .findFirst()
                    .map(p -> members.findByPartnershipIdOrderByAddedAtAsc(p.getId()).stream()
                            .map(VoucherPartnershipMerchant::getMerchantId)
                            .collect(Collectors.toSet()))
                    .orElse(Set.of());
            for (Merchant t : e.getValue()) {
                if (!in.contains(issuer.getId()) || !in.contains(t.getId())) {
                    refused.add(t);
                }
            }
        }
        return refused;
    }

    /** True while InnBucks has suspended the partnership between these two tenants. */
    @Transactional(readOnly = true)
    public boolean suspendedBetween(UUID tenantA, UUID tenantB) {
        return !tenantA.equals(tenantB)
                && !partnerships.findBetween(tenantA, tenantB, EnumSet.of(VoucherPartnership.Status.SUSPENDED))
                        .isEmpty();
    }

    // ================================================================ helpers

    private Tenant tenantByCode(String code) {
        List<Tenant> found = tenants.findAllByCodeIgnoreCase(code == null ? "" : code.strip());
        if (found.size() != 1) {
            throw LoyaltyException.notFound("tenant");
        }
        return found.get(0);
    }

    /**
     * The acting tenant's own active merchants, each one the caller may put in:
     * a tenant-level caller any merchant of the tenant, a merchant admin only the
     * merchants it administers (its organization's).
     */
    private List<Merchant> requireOwnMerchants(UUID tenantId, List<UUID> ids) {
        boolean tenantLevel = CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", "ROLE_TENANT_ADMIN", "ROLE_PLATFORM_ADMIN");
        List<Merchant> out = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            Merchant m = tenantLevel
                    ? merchants.findById(id).filter(found -> found.getTenantId().equals(tenantId))
                            .orElseThrow(() -> LoyaltyException.notFound("merchant"))
                    : merchantAuthz.requireCallerAdministersMerchant(tenantId, id);
            if (m.getStatus() != Merchant.Status.ACTIVE) {
                throw LoyaltyException.badRequest("MERCHANT_INACTIVE", m.getName() + " is not active.");
            }
            out.add(m);
        }
        return out;
    }

    private VoucherPartnership lockInvolving(UUID tenantId, UUID id) {
        return partnerships.lockById(id).filter(p -> p.involves(tenantId))
                .orElseThrow(() -> LoyaltyException.notFound("partnership"));
    }

    private static void requireInvitee(VoucherPartnership p, UUID tenantId) {
        if (p.getStatus() != VoucherPartnership.Status.INVITED) {
            throw LoyaltyException.conflict("PARTNERSHIP_NOT_PENDING", "This partnership is not waiting for an answer.");
        }
        if (!p.getInviteeTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("NOT_INVITED_TENANT", "Only the invited tenant can accept or decline.");
        }
    }

    private static void requireLive(VoucherPartnership p) {
        if (!p.getStatus().live()) {
            throw LoyaltyException.conflict("PARTNERSHIP_NOT_LIVE", "This partnership has ended.");
        }
    }

    private void decide(VoucherPartnership p, Instant now) {
        p.setDecidedBy(actor());
        p.setDecidedOnBehalf(onBehalf());
        p.setDecidedAt(now);
    }

    private void addSide(VoucherPartnership p, UUID tenantId, List<Merchant> side, String actor, Instant now) {
        for (Merchant m : side) {
            VoucherPartnershipMerchant row = new VoucherPartnershipMerchant();
            row.setPartnershipId(p.getId());
            row.setMerchantId(m.getId());
            row.setTenantId(tenantId);
            row.setAddedBy(actor);
            row.setAddedAt(now);
            members.save(row);
        }
    }

    private void replaceSide(VoucherPartnership p, UUID tenantId, List<Merchant> side, Instant now) {
        members.deleteSide(p.getId(), tenantId);
        members.flush();
        addSide(p, tenantId, side, actor(), now);
    }

    private static LoyaltyException exists() {
        return LoyaltyException.conflict("PARTNERSHIP_EXISTS",
                "These two tenants already have a partnership. End it before starting another.");
    }

    private static boolean onBehalf() {
        return CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN");
    }

    private static String actor() {
        String who = CallerDetails.currentEmail();
        if (who != null && !who.isBlank()) {
            return who.length() > 254 ? who.substring(0, 254) : who;
        }
        UUID id = CallerDetails.currentUserId();
        return id != null ? id.toString() : "unknown";
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private PartnershipDtos.PartnershipView view(VoucherPartnership p) {
        return views(List.of(p)).get(0);
    }

    /** Views for several partnerships, from four reads whatever their number. */
    private List<PartnershipDtos.PartnershipView> views(List<VoucherPartnership> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        members.flush();
        List<VoucherPartnershipMerchant> rows = members.findByPartnershipIdIn(
                list.stream().map(VoucherPartnership::getId).toList());
        Set<UUID> tenantIds = new HashSet<>();
        list.forEach(p -> {
            tenantIds.add(p.getInviterTenantId());
            tenantIds.add(p.getInviteeTenantId());
        });
        Map<UUID, Tenant> tenantById = tenants.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));
        Map<UUID, Merchant> merchantById = merchants.findAllById(
                        rows.stream().map(VoucherPartnershipMerchant::getMerchantId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Merchant::getId, Function.identity()));
        Map<UUID, List<VoucherPartnershipMerchant>> rowsByPartnership = rows.stream()
                .collect(Collectors.groupingBy(VoucherPartnershipMerchant::getPartnershipId));
        boolean people = CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN");
        List<PartnershipDtos.PartnershipView> out = new ArrayList<>();
        for (VoucherPartnership p : list) {
            List<VoucherPartnershipMerchant> its = rowsByPartnership.getOrDefault(p.getId(), List.of());
            out.add(new PartnershipDtos.PartnershipView(p.getId(), p.getStatus().name(), p.isAutoAccepted(),
                    side(p.getInviterTenantId(), its, tenantById, merchantById),
                    side(p.getInviteeTenantId(), its, tenantById, merchantById),
                    p.getInvitedAt(), p.getDecidedAt(), p.getEndedAt(), p.getSuspendedAt(), p.getSuspensionReason(),
                    people ? p.getInvitedBy() : null, people ? p.getDecidedBy() : null,
                    people ? p.getEndedBy() : null, people ? p.getSuspendedBy() : null));
        }
        return out;
    }

    private static PartnershipDtos.Side side(UUID tenantId, List<VoucherPartnershipMerchant> rows,
                                              Map<UUID, Tenant> tenantById, Map<UUID, Merchant> merchantById) {
        Tenant t = tenantById.get(tenantId);
        List<PartnershipDtos.MerchantView> ms = rows.stream()
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(r -> new PartnershipDtos.MerchantView(r.getMerchantId(),
                        merchantById.containsKey(r.getMerchantId()) ? merchantById.get(r.getMerchantId()).getName()
                                : null))
                .toList();
        return new PartnershipDtos.Side(tenantId, t == null ? null : t.getCode(), t == null ? null : t.getName(), ms);
    }
}
