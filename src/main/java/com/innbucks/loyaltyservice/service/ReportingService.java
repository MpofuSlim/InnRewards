package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.RedemptionDetail;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherDetail;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherReport;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherSummary;
import com.innbucks.loyaltyservice.dto.VoucherReportFilters;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.VoucherTemplateRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.util.VoucherCodes;
import org.springframework.data.domain.PageRequest;

@Service
@Transactional(readOnly = true)
public class ReportingService {

    private final TenantRepository tenants;
    private final MerchantRepository merchants;
    private final LoyaltyUserRepository users;
    private final LoyaltyTransactionRepository transactions;
    private final VoucherRepository vouchers;
    private final InvoiceRepository invoices;
    private final FraudAttemptRepository fraud;
    private final CampaignRepository campaigns;
    // Tenant-scope guards reused from the owning services: requireMerchant /
    // require throw CROSS_TENANT (403) when the path id belongs to another
    // tenant, closing the reporting IDOR on /merchant, /points/merchant, /points/user.
    private final MerchantService merchantService;
    private final UserService userService;
    // Voucher-report enrichment: shop guard + name lookups, template names, and
    // the per-voucher redemption log for the single-voucher detail view.
    private final ShopService shopService;
    private final ShopRepository shops;
    private final VoucherTemplateRepository voucherTemplates;
    private final VoucherRedemptionRepository voucherRedemptions;
    // Merchant-360 report: rules block (merchant overrides + tenant templates).
    private final LoyaltyRuleRepository rules;

    // The CSV exports run inside this class's one read-only transaction, so every
    // page they load would stay in the persistence context until the export
    // ends: the whole period in memory again, as entities instead of a String.
    // They clear it after each page (see detachPage). Field-injected so the unit
    // tests that construct this class by hand need no change; null there, where
    // there is no persistence context to grow.
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ReportingService(TenantRepository tenants, MerchantRepository merchants,
                            LoyaltyUserRepository users,
                            LoyaltyTransactionRepository transactions,
                            VoucherRepository vouchers,
                            InvoiceRepository invoices,
                            FraudAttemptRepository fraud,
                            CampaignRepository campaigns,
                            MerchantService merchantService,
                            UserService userService,
                            ShopService shopService,
                            ShopRepository shops,
                            VoucherTemplateRepository voucherTemplates,
                            VoucherRedemptionRepository voucherRedemptions,
                            LoyaltyRuleRepository rules) {
        this.tenants = tenants;
        this.merchants = merchants;
        this.users = users;
        this.transactions = transactions;
        this.vouchers = vouchers;
        this.invoices = invoices;
        this.fraud = fraud;
        this.campaigns = campaigns;
        this.merchantService = merchantService;
        this.userService = userService;
        this.shopService = shopService;
        this.shops = shops;
        this.voucherTemplates = voucherTemplates;
        this.voucherRedemptions = voucherRedemptions;
        this.rules = rules;
    }

    public Dtos.OperatorDashboard operator() {
        Instant startOfDay = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = startOfDay.plusSeconds(86_400);
        Instant in7 = startOfDay.plusSeconds(7 * 86_400);
        Instant in30 = startOfDay.plusSeconds(30 * 86_400);
        Instant since24h = Instant.now().minusSeconds(86_400);

        // The platform-internal ticketing container tenant (fixed id, seeded by
        // V23) is NOT a real operator tenant — it books ticket-purchase loyalty.
        // Exclude it and its merchant(s) from every operator-overview figure so
        // the dashboard matches the visible tenant listing (which already hides it).
        UUID ticketing = TicketingLoyaltyService.TICKETING_TENANT_ID;

        long totalTenants = tenants.countByIdNot(ticketing);
        long activeMerchants = merchants.countByTenantIdNotAndStatus(ticketing, Merchant.Status.ACTIVE);
        long txnsToday = transactions.countSinceExcludingTenant(startOfDay, ticketing);

        // Every figure below used to be four queries PER MERCHANT, summed in a
        // loop over merchants.findAll(). Each is now one query over the same
        // rows: the per-merchant predicate with "this merchant" replaced by
        // "any merchant outside the ticketing tenant".
        List<Object[]> points = transactions.sumPointsIssuedAndRedeemedExcludingTenant(
                ticketing, startOfDay, endOfDay);
        Object[] pointsRow = points.isEmpty() ? new Object[]{null, null} : points.get(0);
        BigDecimal pointsIssuedToday = nz(toBigDecimalOrNull(pointsRow[0]));
        BigDecimal pointsRedeemedToday = nz(toBigDecimalOrNull(pointsRow[1]));
        long vouchersIssuedToday = vouchers.countIssuedBetweenExcludingTenant(ticketing, startOfDay, endOfDay);
        long vouchersRedeemedToday = vouchers.countRedeemedBetweenExcludingTenant(ticketing, startOfDay, endOfDay);

        long fraudAttempts = fraud.countByCreatedAtAfter(since24h);
        long invoicesPending = invoices.countByStatus(Invoice.Status.PENDING);
        long invoicesPaid = invoices.countByStatus(Invoice.Status.PAID);

        long expiringIn7 = vouchers.countExpired(in7);
        long expiringIn30 = vouchers.countExpired(in30);

        return new Dtos.OperatorDashboard(totalTenants, activeMerchants, txnsToday,
                vouchersIssuedToday, vouchersRedeemedToday,
                pointsIssuedToday, pointsRedeemedToday, fraudAttempts,
                invoicesPending, invoicesPaid, expiringIn7, expiringIn30);
    }

    /**
     * The tenant dashboard narrowed to a merchant scope ({@code null} = the whole
     * tenant — see {@code MerchantAuthz.readableMerchants}). Tenant-wide
     * campaigns (no merchant) count for a scoped caller: they apply to its
     * merchants too, exactly as the merchant-360 report shows them.
     */
    public Dtos.TenantDashboard tenant(UUID tenantId, Set<UUID> scope) {
        if (scope == null) {
            return tenant(tenantId);
        }
        long activeCampaigns = campaigns.findByTenantId(tenantId).stream()
                .filter(Campaign::isActive)
                .filter(c -> c.getMerchantId() == null || scope.contains(c.getMerchantId()))
                .count();
        if (scope.isEmpty()) {
            return new Dtos.TenantDashboard(tenantId, 0, activeCampaigns, 0, 0, BigDecimal.ZERO, 0);
        }
        long outstanding = Voucher.LIVE_STATUSES.stream()
                .mapToLong(st -> vouchers.countByTenantIdAndMerchantIdInAndStatus(tenantId, scope, st))
                .sum();
        long expired = vouchers.countByTenantIdAndMerchantIdInAndStatus(tenantId, scope, Voucher.Status.EXPIRED);
        BigDecimal totalBalance = nz(transactions.sumNetPointsForMerchants(tenantId, scope));
        long pending = invoices.findByTenantIdAndStatus(tenantId, Invoice.Status.PENDING).stream()
                .filter(i -> scope.contains(i.getMerchantId()))
                .count();
        return new Dtos.TenantDashboard(tenantId, scope.size(), activeCampaigns,
                outstanding, expired, totalBalance, pending);
    }

    public Dtos.TenantDashboard tenant(UUID tenantId) {
        long merchantCount = merchants.findByTenantId(tenantId).size();
        long activeCampaigns = campaigns.findByTenantId(tenantId).stream()
                .filter(Campaign::isActive).count();
        long outstanding = Voucher.LIVE_STATUSES.stream()
                .mapToLong(st -> vouchers.countByTenantIdAndStatus(tenantId, st))
                .sum();
        long expired = vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.EXPIRED);
        // Points are GLOBAL per customer now (wallets aren't tenant-scoped), so a
        // tenant's outstanding points come from the ledger — net points it
        // originated (issued minus redeemed) — not from wallet balances.
        BigDecimal totalBalance = transactions.sumNetPointsByTenant(tenantId);
        if (totalBalance == null) totalBalance = BigDecimal.ZERO;
        long pending = invoices.findByTenantIdAndStatus(tenantId, Invoice.Status.PENDING).size();
        return new Dtos.TenantDashboard(tenantId, merchantCount, activeCampaigns,
                outstanding, expired, totalBalance, pending);
    }

    public Dtos.MerchantDashboard merchant(UUID tenantId, UUID merchantId) {
        // Tenant scope: reject (403 CROSS_TENANT / 404) a merchant in another
        // tenant before aggregating any of its data. Previously absent — any
        // MERCHANT_ADMIN/SHOP_ADMIN could read any tenant's merchant dashboard.
        merchantService.requireMerchant(tenantId, merchantId);
        Instant from = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = from.plusSeconds(86_400);
        Instant since24h = Instant.now().minusSeconds(86_400);
        Merchant m = merchants.findById(merchantId).orElse(null);

        // Pull the individual rows so the dashboard's "estimated invoice"
        // figure honours the merchant's PERCENTAGE / FIXED_PLUS_PERCENTAGE
        // configuration. Without this we'd still be reporting count*flat —
        // the same regression the invoicing path used to have.
        List<Voucher> issuedVouchers   = vouchers.findByMerchantIdAndIssuedAtBetween(merchantId, from, to);
        List<Voucher> redeemedVouchers = vouchers.findByMerchantIdAndRedeemedAtBetween(merchantId, from, to);
        long issued   = issuedVouchers.size();
        long redeemed = redeemedVouchers.size();
        long today    = redeemed;
        BigDecimal pointsIssued = transactions.sumPointsIssued(merchantId, from, to);
        BigDecimal pointsRedeemed = transactions.sumPointsRedeemed(merchantId, from, to);
        long fraudAlerts = fraud.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(f -> merchantId.equals(f.getMerchantId()))
                .filter(f -> f.getCreatedAt().isAfter(since24h))
                .count();
        LocalDate nextInvoice = m == null ? null : switch (m.getBillingCycle()) {
            // DAILY invoices each completed day, so the next run is tomorrow.
            case DAILY -> LocalDate.now().plusDays(1);
            case WEEKLY -> LocalDate.now().plusWeeks(1).with(java.time.DayOfWeek.MONDAY);
            case MONTHLY -> LocalDate.now().withDayOfMonth(1).plusMonths(1);
        };
        // Fee schedule resolved the same way the invoice run resolves it (V29:
        // merchant rule -> merchant record -> global rule), so the estimate and
        // the eventual bill can't drift apart.
        BigDecimal estimatedInvoice = BigDecimal.ZERO;
        if (m != null) {
            EffectiveFees fees = EffectiveFees.resolve(m,
                    rules.findApplicable(m.getTenantId(), merchantId, TransactionType.PURCHASE),
                    Instant.now());
            estimatedInvoice = issuedVouchers.stream()
                    .map(fees::feeForIssued)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(redeemedVouchers.stream()
                            .map(fees::feeForRedeemed)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return new Dtos.MerchantDashboard(merchantId, today, issued, redeemed,
                pointsIssued, pointsRedeemed, fraudAlerts, nextInvoice, estimatedInvoice);
    }

    /**
     * Merchant-360 report: every merchant under the tenant with its full detail —
     * identity + configuration, shops, applicable rules, campaigns, lifetime
     * points + voucher activity, complete billing picture, and headline stats.
     *
     * <p><b>Visibility (A01):</b> tenant membership alone doesn't grant a view of
     * every merchant's billing/points data, so results are scoped to the caller,
     * mirroring {@code MerchantAuthz}'s ownership model:
     * <ul>
     *   <li>SUPER_ADMIN / TENANT_ADMIN / PLATFORM_ADMIN — every merchant in the tenant;</li>
     *   <li>SHOP_ADMIN / SHOP_USER — only the merchant pinned in their JWT claim;</li>
     *   <li>MERCHANT_ADMIN — only merchants their organization owns.</li>
     * </ul>
     * Rather than 403-ing, out-of-scope merchants are simply absent — the list is
     * "everything you administer", whoever asks.
     *
     * <p>Pagination slices AFTER the visibility filter (name-ordered) so page
     * numbers are stable per caller. Tenant-wide rules + campaigns are fetched
     * once and reused across every merchant on the page.
     *
     * <p><b>Cost is fixed per page, not per merchant.</b> Every per-merchant read
     * (shops, points, transaction mix, voucher summary, invoices, the current
     * period's fee estimate, stats) runs ONCE for the whole page as a grouped
     * query keyed by merchant and is assembled in memory — see
     * {@link MerchantPageData}. It used to be seventeen queries per merchant.
     */
    public Page<Dtos.MerchantFullReport> merchantFullReports(UUID tenantId, Pageable pageable) {
        List<Merchant> visible = merchants.findByTenantId(tenantId).stream()
                .filter(this::callerMaySeeMerchant)
                .sorted(Comparator.comparing(Merchant::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        List<LoyaltyRule> tenantRules = rules.findByTenantId(tenantId);
        List<Campaign> tenantCampaigns = campaigns.findByTenantId(tenantId);

        int start = (int) Math.min(pageable.getOffset(), visible.size());
        int end = Math.min(start + pageable.getPageSize(), visible.size());
        List<Merchant> pageMerchants = visible.subList(start, end);
        if (pageMerchants.isEmpty()) {
            // Nothing to aggregate — and never an IN () below.
            return new PageImpl<>(List.of(), pageable, visible.size());
        }
        Instant now = Instant.now();
        MerchantPageData data = loadMerchantPage(tenantId, pageMerchants, now);
        List<Dtos.MerchantFullReport> content = pageMerchants.stream()
                .map(m -> buildMerchantFullReport(m, tenantRules, tenantCampaigns, data, now))
                .toList();
        return new PageImpl<>(content, pageable, visible.size());
    }

    /** Mirrors MerchantAuthz's ownership model as a non-throwing predicate. */
    private boolean callerMaySeeMerchant(Merchant m) {
        if (CallerDetails.hasAnyRole("ROLE_SUPER_ADMIN", "ROLE_TENANT_ADMIN", "ROLE_PLATFORM_ADMIN")) {
            return true;
        }
        UUID scopedMerchant = CallerDetails.currentMerchantId();   // SHOP_ADMIN / SHOP_USER
        if (scopedMerchant != null) {
            return scopedMerchant.equals(m.getId());
        }
        UUID callerOrganization = CallerDetails.currentOrganizationId();   // MERCHANT_ADMIN
        return callerOrganization != null && callerOrganization.equals(m.getOrganizationId());
    }

    /** One voucher's contribution to a fee estimate: when it happened and its face value. */
    private record FaceValueAt(Instant at, BigDecimal faceValue) {}

    /** A merchant's transaction activity: what {@code activityByMerchant} returns per row. */
    private record TxnActivity(long countInWindow, Instant first, Instant last, long distinctUsers) {
        static final TxnActivity NONE = new TxnActivity(0, null, null, 0);
    }

    /**
     * Everything the merchant-360 report reads per merchant, loaded for a whole
     * page with one grouped query per block. A merchant absent from a map had no
     * matching rows, which is the zero / empty / null the per-merchant query
     * answered for it.
     */
    private record MerchantPageData(
            Map<UUID, List<Shop>> shops,
            Map<UUID, BigDecimal[]> points,
            Map<UUID, Map<String, Long>> txnsByType,
            Map<UUID, TxnActivity> activity,
            Map<UUID, List<Object[]>> voucherStatus,
            Map<UUID, BigDecimal> redeemedValue,
            Map<UUID, Long> issued30,
            Map<UUID, Long> redeemed30,
            Map<UUID, List<Invoice>> invoices,
            Map<UUID, List<FaceValueAt>> periodIssued,
            Map<UUID, List<FaceValueAt>> periodRedeemed,
            Map<UUID, Long> fraud30,
            Map<UUID, Long> expiring30) {}

    /**
     * Loads {@link MerchantPageData} for a non-empty page: a fixed number of
     * queries whatever the page size. Each grouped query keeps its per-merchant
     * twin's predicate (window bounds, BETWEEN inclusivity, status filters), so
     * every figure is computed over exactly the rows it was before.
     */
    private MerchantPageData loadMerchantPage(UUID tenantId, List<Merchant> page, Instant now) {
        List<UUID> ids = page.stream().map(Merchant::getId).toList();
        Instant epoch = Instant.EPOCH;
        Instant thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);

        Map<UUID, List<Shop>> shopsByMerchant = new HashMap<>();
        for (Shop s : shops.findByTenantIdAndMerchantIdIn(tenantId, ids)) {
            shopsByMerchant.computeIfAbsent(s.getMerchantId(), k -> new ArrayList<>()).add(s);
        }

        Map<UUID, BigDecimal[]> points = new HashMap<>();
        for (Object[] r : transactions.sumPointsByMerchant(ids, epoch, now)) {
            points.put((UUID) r[0], new BigDecimal[]{toBigDecimalOrNull(r[1]), toBigDecimalOrNull(r[2])});
        }

        Map<UUID, Map<String, Long>> byType = new HashMap<>();
        for (Object[] r : transactions.countByTypeByMerchant(tenantId, ids, epoch, now)) {
            byType.computeIfAbsent((UUID) r[0], k -> new TreeMap<>())
                    .put(String.valueOf(r[1]), ((Number) r[2]).longValue());
        }

        Map<UUID, TxnActivity> activity = new HashMap<>();
        for (Object[] r : transactions.activityByMerchant(ids, epoch, now)) {
            activity.put((UUID) r[0], new TxnActivity(
                    r[1] == null ? 0 : ((Number) r[1]).longValue(),
                    toInstantOrNull(r[2]), toInstantOrNull(r[3]),
                    r[4] == null ? 0 : ((Number) r[4]).longValue()));
        }

        Map<UUID, List<Object[]>> voucherStatus = new HashMap<>();
        for (Object[] r : vouchers.summaryByMerchantAndStatus(ids, epoch, now)) {
            voucherStatus.computeIfAbsent((UUID) r[0], k -> new ArrayList<>())
                    .add(new Object[]{r[1], r[2], r[3]});
        }

        Map<UUID, BigDecimal> redeemedValue = new HashMap<>();
        for (Object[] r : vouchers.sumRedeemedValueByMerchantIds(ids)) {
            redeemedValue.put((UUID) r[0], toBigDecimalOrNull(r[1]));
        }

        Map<UUID, List<Invoice>> invoicesByMerchant = new HashMap<>();
        // Ordered by periodEnd DESC overall, so each merchant's list keeps that order.
        for (Invoice inv : invoices.findByMerchantIdInOrderByPeriodEndDesc(ids)) {
            invoicesByMerchant.computeIfAbsent(inv.getMerchantId(), k -> new ArrayList<>()).add(inv);
        }

        // The fee estimate's window starts at each merchant's own current billing
        // period. One read from the EARLIEST of those starts, then each merchant
        // keeps only the rows inside its own [periodFrom, now] — BETWEEN, both
        // ends inclusive, exactly as the per-merchant query bounded them.
        LocalDate today = LocalDate.now();
        Instant earliest = page.stream().map(m -> currentPeriodFrom(m, today))
                .min(Comparator.naturalOrder()).orElse(now);
        Map<UUID, List<FaceValueAt>> periodIssued = faceValuesByMerchant(
                vouchers.issuedFaceValuesBetween(ids, earliest, now));
        Map<UUID, List<FaceValueAt>> periodRedeemed = faceValuesByMerchant(
                vouchers.redeemedFaceValuesBetween(ids, earliest, now));

        return new MerchantPageData(shopsByMerchant, points, byType, activity, voucherStatus, redeemedValue,
                countsByMerchant(vouchers.countIssuedBetweenByMerchant(ids, thirtyDaysAgo, now)),
                countsByMerchant(vouchers.countRedeemedBetweenByMerchant(ids, thirtyDaysAgo, now)),
                invoicesByMerchant, periodIssued, periodRedeemed,
                countsByMerchant(fraud.countByMerchantSince(ids, thirtyDaysAgo)),
                countsByMerchant(vouchers.countExpiringBetweenByMerchant(ids, now,
                        now.plus(30, ChronoUnit.DAYS), Voucher.LIVE_STATUSES)));
    }

    private static Map<UUID, Long> countsByMerchant(List<Object[]> rows) {
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return out;
    }

    private static Map<UUID, List<FaceValueAt>> faceValuesByMerchant(List<Object[]> rows) {
        Map<UUID, List<FaceValueAt>> out = new HashMap<>();
        for (Object[] r : rows) {
            out.computeIfAbsent((UUID) r[0], k -> new ArrayList<>())
                    .add(new FaceValueAt(toInstantOrNull(r[1]), toBigDecimalOrNull(r[2])));
        }
        return out;
    }

    /** Start of the merchant's current (not yet invoiced) billing period, UTC. */
    private static Instant currentPeriodFrom(Merchant m, LocalDate today) {
        LocalDate currentPeriodStart = switch (m.getBillingCycle()) {
            case DAILY -> today;
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            case MONTHLY -> today.withDayOfMonth(1);
        };
        return currentPeriodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static boolean within(Instant at, Instant from, Instant to) {
        return at != null && !at.isBefore(from) && !at.isAfter(to);
    }

    private Dtos.MerchantFullReport buildMerchantFullReport(Merchant m,
                                                            List<LoyaltyRule> tenantRules,
                                                            List<Campaign> tenantCampaigns,
                                                            MerchantPageData data,
                                                            Instant now) {
        UUID id = m.getId();

        // Shops — every outlet under the merchant.
        List<Dtos.ShopResponse> shopList = data.shops().getOrDefault(id, List.of()).stream()
                .map(s -> new Dtos.ShopResponse(s.getId(), s.getTenantId(), s.getMerchantId(),
                        s.getName(), s.getAddress(), s.getStatus(), s.getCreatedAt()))
                .toList();

        // Rules — the merchant's own overrides plus tenant-global templates.
        List<Dtos.RuleLine> ruleLines = tenantRules.stream()
                .filter(r -> r.getMerchantId() == null || id.equals(r.getMerchantId()))
                .map(r -> new Dtos.RuleLine(r.getId(),
                        r.getMerchantId() == null ? "TENANT_GLOBAL" : "MERCHANT",
                        r.getTransactionType() == null ? null : r.getTransactionType().name(),
                        r.getPointsPerUnit(), r.getMultiplier(), r.getMaxPointsPerTxn(),
                        r.getPocket(), r.isActive(), r.getStartsAt(), r.getEndsAt()))
                .toList();

        // Campaigns — merchant-targeted plus tenant-wide (null merchantId).
        List<Dtos.CampaignLine> campaignLines = tenantCampaigns.stream()
                .filter(c -> c.getMerchantId() == null || id.equals(c.getMerchantId()))
                .map(c -> new Dtos.CampaignLine(c.getId(), c.getName(),
                        c.getTransactionType() == null ? null : c.getTransactionType().name(),
                        c.getMultiplier(), c.isActive(), c.getStartsAt(), c.getEndsAt(),
                        c.getMatchedTransactions()))
                .toList();

        // Points — lifetime activity. A merchant with no POSTED rows has no
        // grouped row, which is the COALESCE-to-0 the per-merchant sums answered.
        BigDecimal[] pts = data.points().get(id);
        BigDecimal ptsIssued = nz(pts == null ? null : pts[0]);
        BigDecimal ptsRedeemed = nz(pts == null ? null : pts[1]);
        TxnActivity act = data.activity().getOrDefault(id, TxnActivity.NONE);
        Dtos.PointsSummary points = new Dtos.PointsSummary(
                ptsIssued, ptsRedeemed, ptsIssued.subtract(ptsRedeemed),
                act.countInWindow(),
                data.txnsByType().getOrDefault(id, new TreeMap<>()),
                act.first(),
                act.last());

        // Vouchers — lifetime status breakdown + face values (baseValue, USD).
        Map<String, Long> vouchersByStatus = new TreeMap<>();
        long voucherTotal = 0;
        BigDecimal valueIssued = BigDecimal.ZERO;
        for (Object[] row : data.voucherStatus().getOrDefault(id, List.of())) {
            long count = ((Number) row[1]).longValue();
            vouchersByStatus.put(String.valueOf(row[0]), count);
            voucherTotal += count;
            valueIssued = valueIssued.add((BigDecimal) row[2]);
        }
        Dtos.VoucherSummary voucherSummary = new Dtos.VoucherSummary(
                voucherTotal, vouchersByStatus, valueIssued,
                nz(data.redeemedValue().get(id)),
                data.issued30().getOrDefault(id, 0L),
                data.redeemed30().getOrDefault(id, 0L));

        // Invoices — full history rolled up, most recent 12 inlined.
        List<Invoice> invoiceRows = data.invoices().getOrDefault(id, List.of());
        long pending = 0, paid = 0, overdue = 0, cancelled = 0;
        BigDecimal billed = BigDecimal.ZERO, paidAmount = BigDecimal.ZERO, outstanding = BigDecimal.ZERO;
        for (Invoice inv : invoiceRows) {
            billed = billed.add(inv.getTotalAmount());
            switch (inv.getStatus()) {
                case PENDING -> { pending++; outstanding = outstanding.add(inv.getTotalAmount()); }
                case PAID -> { paid++; paidAmount = paidAmount.add(inv.getTotalAmount()); }
                case OVERDUE -> { overdue++; outstanding = outstanding.add(inv.getTotalAmount()); }
                case CANCELLED -> cancelled++;
            }
        }
        LocalDate today = LocalDate.now();
        LocalDate nextInvoice = switch (m.getBillingCycle()) {
            case DAILY -> today.plusDays(1);
            case WEEKLY -> today.plusWeeks(1).with(java.time.DayOfWeek.MONDAY);
            case MONTHLY -> today.withDayOfMonth(1).plusMonths(1);
        };
        // Fees accrued in the CURRENT (not yet invoiced) billing period, priced
        // per voucher with the merchant's fee model — same math the invoice run
        // will apply, so the figure previews the next bill.
        Instant periodFrom = currentPeriodFrom(m, today);
        EffectiveFees fees = EffectiveFees.resolve(m,
                EffectiveFees.applicable(tenantRules, id, TransactionType.PURCHASE), now);
        BigDecimal estimatedFees = data.periodIssued().getOrDefault(id, List.of()).stream()
                .filter(f -> within(f.at(), periodFrom, now))
                .map(f -> fees.feeForIssuedFaceValue(f.faceValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(data.periodRedeemed().getOrDefault(id, List.of()).stream()
                        .filter(f -> within(f.at(), periodFrom, now))
                        .map(f -> fees.feeForRedeemedFaceValue(f.faceValue()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        Dtos.InvoiceSummary invoiceSummary = new Dtos.InvoiceSummary(
                invoiceRows.size(), pending, paid, overdue, cancelled,
                billed, paidAmount, outstanding, nextInvoice, estimatedFees,
                invoiceRows.stream().limit(12).map(InvoicingService::toResponse).toList());

        // Headline stats.
        Dtos.MerchantStats stats = new Dtos.MerchantStats(
                shopList.size(),
                shopList.stream().filter(s -> s.status() == Shop.Status.ACTIVE).count(),
                act.distinctUsers(),
                data.fraud30().getOrDefault(id, 0L),
                campaignLines.stream().filter(Dtos.CampaignLine::active).count(),
                data.expiring30().getOrDefault(id, 0L));

        return new Dtos.MerchantFullReport(id, m.getTenantId(), m.getName(), m.getCategory(),
                m.getCurrency(), m.getBillingCycle(), m.getStatus(), m.getOrganizationId(), m.getCreatedAt(),
                new Dtos.FeeModel(m.getFeeIssuedType(), m.getFeeIssuedFixed(), m.getFeeIssuedPercentage()),
                new Dtos.FeeModel(m.getFeeRedeemedType(), m.getFeeRedeemedFixed(), m.getFeeRedeemedPercentage()),
                shopList, ruleLines, campaignLines, points, voucherSummary, invoiceSummary, stats);
    }

    /** Mock/driver safety: aggregate queries COALESCE to 0, but a null must never NPE a report. */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** A projected aggregate as a BigDecimal, keeping a SQL NULL as null (and the
     *  driver's own scale — {@link #toBigDecimal} would turn a null into ZERO). */
    private static BigDecimal toBigDecimalOrNull(Object o) {
        return o == null ? null : toBigDecimal(o);
    }

    public Map<String, Long> transactionMix(UUID tenantId, UUID merchantId,
                                            LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Long> out = new HashMap<>();
        List<Object[]> rows = transactions.countByType(tenantId, merchantId, fromInstant, toInstant);
        for (Object[] r : rows) {
            out.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        return out;
    }

    /** {@link #transactionMix} over a merchant scope ({@code null} = the whole tenant). */
    public Map<String, Long> transactionMix(UUID tenantId, Set<UUID> scope,
                                            LocalDate from, LocalDate to) {
        if (scope == null) {
            return transactionMix(tenantId, (UUID) null, from, to);
        }
        Map<String, Long> out = new HashMap<>();
        if (scope.isEmpty()) {
            return out;
        }
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        for (Object[] r : transactions.countByTypeForMerchants(tenantId, scope, fromInstant, toInstant)) {
            out.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        return out;
    }

    /** {@link #recentFraud(UUID)} over a merchant scope ({@code null} = the whole
     *  tenant). A scoped caller sees only attempts against its own merchants —
     *  an attempt with no merchant (an unknown code) names nobody's business. */
    public List<Dtos.FraudAttemptResponse> recentFraud(UUID tenantId, Set<UUID> scope) {
        if (scope == null) {
            return recentFraud(tenantId);
        }
        if (scope.isEmpty()) {
            return List.of();
        }
        return fraud.findTop100ByTenantIdAndMerchantIdInOrderByCreatedAtDesc(tenantId, scope).stream()
                .map(ReportingService::toFraudResponse)
                .toList();
    }

    private static Dtos.FraudAttemptResponse toFraudResponse(com.innbucks.loyaltyservice.entity.FraudAttempt f) {
        return new Dtos.FraudAttemptResponse(f.getId(), f.getVoucherCode(),
                f.getMerchantId(), f.getReason().name(), f.getDetail(),
                f.getDeviceFingerprint(), f.getCreatedAt());
    }

    public List<Dtos.FraudAttemptResponse> recentFraud(UUID tenantId) {
        return fraud.findTop100ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(f -> new Dtos.FraudAttemptResponse(f.getId(), f.getVoucherCode(),
                        f.getMerchantId(), f.getReason().name(), f.getDetail(),
                        f.getDeviceFingerprint(), f.getCreatedAt()))
                .toList();
    }

    public Dtos.PointsReport pointsForMerchant(UUID tenantId, UUID merchantId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        // Tenant scope: the tenantId was accepted but never enforced here — a
        // member of tenant A could read tenant B's merchant points report.
        merchantService.requireMerchant(tenantId, merchantId);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal issued = transactions.sumPointsIssued(merchantId, fromInstant, toInstant);
        BigDecimal redeemed = transactions.sumPointsRedeemed(merchantId, fromInstant, toInstant);
        long count = transactions.countByMerchantIdAndCreatedAtBetween(merchantId, fromInstant, toInstant);
        return new Dtos.PointsReport(merchantId, from, to, issued, redeemed,
                issued.subtract(redeemed), count);
    }

    public Dtos.PointsReport pointsForUser(UUID tenantId, UUID userId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        // Tenant scope: previously took no tenantId at all — any admin in any
        // tenant could pull any LoyaltyUser's points statement (cross-tenant IDOR).
        userService.require(tenantId, userId);
        return pointsForResolvedUser(userId, from, to);
    }

    /**
     * Same per-user points statement, resolved by phone number instead of the
     * LoyaltyUser UUID — the phone is the identifier the SuperApp / CS agent
     * actually has. Tenant-scoped by {@link UserService#requireByPhone}: a phone
     * outside this tenant is a 404, not a cross-tenant read.
     */
    public Dtos.PointsReport pointsForUserByPhone(UUID tenantId, String phone, LocalDate from, LocalDate to) {
        requireRange(from, to);
        var u = userService.requireByPhone(tenantId, phone);
        return pointsForResolvedUser(u.getId(), from, to);
    }

    private Dtos.PointsReport pointsForResolvedUser(UUID userId, LocalDate from, LocalDate to) {
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal issued = transactions.sumPointsIssuedByUser(userId, fromInstant, toInstant);
        BigDecimal redeemed = transactions.sumPointsRedeemedByUser(userId, fromInstant, toInstant);
        long count = transactions.countByUserIdAndCreatedAtBetween(userId, fromInstant, toInstant);
        return new Dtos.PointsReport(userId, from, to, issued, redeemed,
                issued.subtract(redeemed), count);
    }

    public Dtos.ShopPointsReport pointsForShop(UUID tenantId, UUID shopId, LocalDate from, LocalDate to,
                                               Pageable pageable) {
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal issued = transactions.sumPointsIssuedByShop(tenantId, shopId, fromInstant, toInstant);
        BigDecimal redeemed = transactions.sumPointsRedeemedByShop(tenantId, shopId, fromInstant, toInstant);
        long count = transactions.countByTenantIdAndShopIdAndCreatedAtBetween(tenantId, shopId, fromInstant, toInstant);
        // Per-customer breakdown: which phone earned/redeemed what at this shop,
        // highest earners first. Rows come back as [phone, issued, redeemed, count].
        List<Dtos.PointsByPhoneRow> byPhone = transactions
                .pointsByPhoneForShop(tenantId, shopId, fromInstant, toInstant).stream()
                .map(r -> {
                    BigDecimal rowIssued = toBigDecimal(r[1]);
                    BigDecimal rowRedeemed = toBigDecimal(r[2]);
                    return new Dtos.PointsByPhoneRow((String) r[0], rowIssued, rowRedeemed,
                            rowIssued.subtract(rowRedeemed), ((Number) r[3]).longValue());
                })
                .sorted(java.util.Comparator.comparing(Dtos.PointsByPhoneRow::pointsIssued).reversed())
                .toList();

        // Per-transaction detail (paginated, newest first): every transaction at
        // the shop with phone, type, amount and points awarded. Phone is resolved
        // from each transaction's userId via one bulk LoyaltyUser lookup (no N+1).
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<LoyaltyTransaction> txnPage = transactions
                .findByTenantIdAndShopIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        tenantId, shopId, fromInstant, toInstant, effective);
        Map<UUID, String> phones = txnPhoneMap(txnPage.getContent());
        String shopName = shops.findById(shopId).map(Shop::getName).orElse(null);
        PageResponse<Dtos.ShopTransactionDetail> txns = PageResponse.from(
                txnPage.map(t -> toShopTransactionDetail(t, phones.get(t.getUserId()), shopName)));

        return new Dtos.ShopPointsReport(shopId, shopName, from, to, issued, redeemed,
                issued.subtract(redeemed), count, byPhone, txns);
    }

    private Map<UUID, String> txnPhoneMap(List<LoyaltyTransaction> txns) {
        Set<UUID> userIds = txns.stream().map(LoyaltyTransaction::getUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> m = new HashMap<>();
        if (userIds.isEmpty()) return m;
        for (com.innbucks.loyaltyservice.entity.LoyaltyUser u : users.findAllById(userIds)) {
            m.put(u.getId(), u.getPhoneNumber());
        }
        return m;
    }

    private static Dtos.ShopTransactionDetail toShopTransactionDetail(LoyaltyTransaction t, String phone, String shopName) {
        BigDecimal points = t.getPointsDelta() == null ? BigDecimal.ZERO : t.getPointsDelta();
        String direction = points.signum() > 0 ? "EARN" : points.signum() < 0 ? "REDEEM" : "NEUTRAL";
        return new Dtos.ShopTransactionDetail(
                t.getId(), t.getCreatedAt(),
                t.getType() == null ? null : t.getType().name(),
                t.getStatus() == null ? null : t.getStatus().name(),
                phone, t.getUserId(),
                t.getShopId(), shopName,
                t.getAmount(), t.getCurrency(),
                points, direction,
                t.getReference(), t.getMerchantId(), t.getRuleId(), t.getCampaignId());
    }

    /** {@link #pointsByType(UUID, UUID, LocalDate, LocalDate)} over a merchant
     *  scope ({@code null} = the whole tenant). */
    public List<Dtos.PointsByTypeRow> pointsByType(UUID tenantId, Set<UUID> scope,
                                                   LocalDate from, LocalDate to) {
        if (scope == null) {
            return pointsByType(tenantId, (UUID) null, from, to);
        }
        requireRange(from, to);
        if (scope.isEmpty()) {
            return List.of();
        }
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return toPointsByTypeRows(
                transactions.sumPointsByTypeForMerchants(tenantId, scope, fromInstant, toInstant));
    }

    public List<Dtos.PointsByTypeRow> pointsByType(UUID tenantId, UUID merchantId,
                                                   LocalDate from, LocalDate to) {
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return toPointsByTypeRows(transactions.sumPointsByType(tenantId, merchantId, fromInstant, toInstant));
    }

    private static List<Dtos.PointsByTypeRow> toPointsByTypeRows(List<Object[]> rows) {
        List<Dtos.PointsByTypeRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            // Row shape: [TransactionType, long count, BigDecimal issued, BigDecimal redeemed].
            out.add(new Dtos.PointsByTypeRow(
                    String.valueOf(r[0]),
                    ((Number) r[1]).longValue(),
                    toBigDecimal(r[2]),
                    toBigDecimal(r[3])));
        }
        return out;
    }

    /** {@link #pointsTimeSeries(UUID, UUID, LocalDate, LocalDate)} over a merchant
     *  scope ({@code null} = the whole tenant). An empty scope is a series of
     *  zero days, not an error. */
    public List<Dtos.PointsTimeSeriesPoint> pointsTimeSeries(UUID tenantId, Set<UUID> scope,
                                                             LocalDate from, LocalDate to) {
        if (scope == null) {
            return pointsTimeSeries(tenantId, (UUID) null, from, to);
        }
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return toSeries(scope.isEmpty() ? List.of()
                : transactions.dailyPointBucketsForMerchants(tenantId, scope, fromInstant, toInstant),
                from, to);
    }

    public List<Dtos.PointsTimeSeriesPoint> pointsTimeSeries(UUID tenantId, UUID merchantId,
                                                             LocalDate from, LocalDate to) {
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return toSeries(transactions.dailyPointBuckets(tenantId, merchantId, fromInstant, toInstant), from, to);
    }

    private static List<Dtos.PointsTimeSeriesPoint> toSeries(List<Object[]> rows, LocalDate from, LocalDate to) {
        // The query skips zero-activity days; we backfill so the FE always
        // gets a contiguous series and can render a chart without holes.
        Map<Instant, Dtos.PointsTimeSeriesPoint> byBucket = new LinkedHashMap<>();
        for (Object[] r : rows) {
            // The native query returns the bucket as a java.sql.Timestamp under
            // both Postgres and H2's PostgreSQL-compat mode; normalise to Instant.
            Instant bucket = toInstantUtc(r[0]);
            BigDecimal issued = toBigDecimal(r[1]);
            BigDecimal redeemed = toBigDecimal(r[2]);
            long count = ((Number) r[3]).longValue();
            byBucket.put(bucket, new Dtos.PointsTimeSeriesPoint(bucket, issued, redeemed, count));
        }

        List<Dtos.PointsTimeSeriesPoint> series = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Instant dayStart = d.atStartOfDay().toInstant(ZoneOffset.UTC);
            Dtos.PointsTimeSeriesPoint p = byBucket.get(dayStart);
            series.add(p != null ? p
                    : new Dtos.PointsTimeSeriesPoint(dayStart, BigDecimal.ZERO, BigDecimal.ZERO, 0));
        }
        return series;
    }

    /**
     * Streams the actual transaction rows (id, createdAt, type, amount,
     * pointsDelta, merchantId, shopId, userId, reference) as a CSV.
     * Before the bugfix this method emitted the transaction-mix counts
     * ("type,count" rows) which (a) contradicted the @Operation summary
     * and (b) returned 6 rows regardless of how many transactions the
     * range contained — useless for the "export my month's transactions"
     * support flow it was supposed to power.
     *
     * <p>Pages through the DB at 500 rows at a time so a busy month
     * doesn't materialise the full result set in memory.
     */
    /** {@link #csv(UUID, UUID, LocalDate, LocalDate)} over a merchant scope
     *  ({@code null} = the whole tenant). An empty scope is the header alone. */
    public String csv(UUID tenantId, Set<UUID> scope, LocalDate from, LocalDate to) {
        try (java.io.StringWriter out = new java.io.StringWriter()) {
            writeCsv(out, tenantId, scope, from, to);
            return out.toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e); // StringWriter.close() never throws
        }
    }

    public String csv(UUID tenantId, UUID merchantId, LocalDate from, LocalDate to) {
        try (java.io.StringWriter out = new java.io.StringWriter()) {
            writeCsvRows(out, tenantId, merchantId, null, from, to);
            return out.toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e); // StringWriter.close() never throws
        }
    }

    /**
     * The transaction export, written to {@code out} a page at a time instead of
     * built as one String. Everything that can refuse the request (the range
     * check) runs BEFORE the first write, so a caller that commits the response
     * only on its first write still answers a bad request with an ordinary error.
     */
    public void writeCsv(java.io.Writer out, UUID tenantId, Set<UUID> scope, LocalDate from, LocalDate to) {
        writeCsvRows(out, tenantId, null, scope, from, to);
    }

    private void writeCsvRows(java.io.Writer out, UUID tenantId, UUID merchantId, Set<UUID> scope,
                              LocalDate from, LocalDate to) {
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        write(out, "id,createdAt,type,amount,pointsDelta,merchantId,shopId,userId,reference,invoiceNumber\n");

        // IN-9: resolve invoice_id -> the human-readable invoice number, which is
        // what someone reconciling points against a bill actually quotes. Cached
        // across pages and fetched in one batch per page, so a month of rows
        // sharing a handful of invoices costs a handful of lookups, not one per
        // row. Blank means the row isn't on any invoice — see LoyaltyTransaction
        // #invoiceId for when that legitimately happens.
        java.util.Map<UUID, String> invoiceNumbers = new java.util.HashMap<>();

        int pageSize = 500;
        int pageNum = 0;
        while (true) {
            if (scope != null && scope.isEmpty()) break;
            var page = scope != null
                    ? transactions.findByTenantIdAndMerchantIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                            tenantId, scope, fromInstant, toInstant, PageRequest.of(pageNum, pageSize))
                    : (merchantId == null)
                    ? transactions.findByTenantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                            tenantId, fromInstant, toInstant, PageRequest.of(pageNum, pageSize))
                    : transactions.findByTenantIdAndMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                            tenantId, merchantId, fromInstant, toInstant, PageRequest.of(pageNum, pageSize));
            List<UUID> unresolved = page.getContent().stream()
                    .map(LoyaltyTransaction::getInvoiceId)
                    .filter(java.util.Objects::nonNull)
                    .filter(id -> !invoiceNumbers.containsKey(id))
                    .distinct()
                    .toList();
            if (!unresolved.isEmpty()) {
                invoices.findAllById(unresolved)
                        .forEach(i -> invoiceNumbers.put(i.getId(), i.getInvoiceNumber()));
            }

            StringBuilder sb = new StringBuilder();
            for (LoyaltyTransaction t : page.getContent()) {
                sb.append(t.getId()).append(',')
                        .append(t.getCreatedAt()).append(',')
                        .append(t.getType()).append(',')
                        .append(csvField(t.getAmount())).append(',')
                        .append(csvField(t.getPointsDelta())).append(',')
                        .append(t.getMerchantId() == null ? "" : t.getMerchantId()).append(',')
                        .append(t.getShopId() == null ? "" : t.getShopId()).append(',')
                        .append(t.getUserId()).append(',')
                        .append(csvField(t.getReference())).append(',')
                        .append(csvField(t.getInvoiceId() == null
                                ? null : invoiceNumbers.get(t.getInvoiceId())))
                        .append('\n');
            }
            write(out, sb);
            boolean last = page.isLast();
            detachPage();
            if (last) break;
            pageNum++;
        }
    }

    /** Writes one chunk of an export and flushes it, so it leaves the process now. */
    private static void write(java.io.Writer out, CharSequence chunk) {
        try {
            out.append(chunk);
            out.flush();
        } catch (java.io.IOException e) {
            // Almost always the client going away mid-download. The response is
            // already committed, so there is nothing left to answer; stop the export.
            throw new java.io.UncheckedIOException("CSV export interrupted", e);
        }
    }

    /**
     * Drops the page just written from the persistence context. The export's
     * transaction is read-only, so nothing is dirty and nothing is lost; without
     * this, every page stays managed until the export ends.
     */
    private void detachPage() {
        if (entityManager != null) entityManager.clear();
    }

    // ==================================================================
    // Detailed voucher reports — operator / tenant / merchant / shop /
    // single-voucher, plus CSV export. See VoucherReportDtos.
    // ==================================================================

    /** A Set view of {@link Voucher#LIVE_STATUSES} for the per-status
     *  breakdown's {@code contains} check — derived, never restated, so the
     *  outstanding figure here can't drift from the outstanding queries. */
    private static final Set<Voucher.Status> OUTSTANDING =
            EnumSet.copyOf(Voucher.LIVE_STATUSES);

    /** Platform-wide voucher report across every real tenant. The internal
     *  ticketing container tenant is excluded, matching the operator dashboard. */
    /** {@link #vouchersForOperator(Voucher.Status, LocalDate, LocalDate, Pageable)} with the optional
     *  report filters (a {@code merchantId} / {@code shopId} filter narrows the platform view). */
    public VoucherReport vouchersForOperator(Voucher.Status status, LocalDate from, LocalDate to,
                                             VoucherReportFilters filters, Pageable pageable) {
        VoucherReportFilters f = filters == null ? VoucherReportFilters.none() : filters;
        return voucherReport("OPERATOR", null, null,
                null, TicketingLoyaltyService.TICKETING_TENANT_ID, f.merchantId(), f.shopId(),
                null, status, from, to, pageable, f);
    }

    public VoucherReport vouchersForOperator(Voucher.Status status, LocalDate from, LocalDate to, Pageable pageable) {
        return voucherReport("OPERATOR", null, null,
                null, TicketingLoyaltyService.TICKETING_TENANT_ID, null, null,
                status, from, to, pageable);
    }

    /** Every voucher in one tenant that the caller's merchant scope admits
     *  ({@code null} = every merchant). */
    /** The tenant voucher report with the optional filters. A {@code merchantId}
     *  / {@code shopId} filter must already be ownership-checked by the caller. */
    public VoucherReport vouchersForTenant(UUID tenantId, Set<UUID> merchantScope, Voucher.Status status,
                                           LocalDate from, LocalDate to, VoucherReportFilters filters,
                                           Pageable pageable) {
        VoucherReportFilters f = filters == null ? VoucherReportFilters.none() : filters;
        return voucherReport("TENANT", tenantId, tenantName(tenantId),
                tenantId, null, f.merchantId(), f.shopId(), merchantScope, status, from, to, pageable, f);
    }

    public VoucherReport vouchersForTenant(UUID tenantId, Set<UUID> merchantScope, Voucher.Status status,
                                           LocalDate from, LocalDate to, Pageable pageable) {
        return voucherReport("TENANT", tenantId, tenantName(tenantId),
                tenantId, null, null, null, merchantScope, status, from, to, pageable);
    }

    /** Every voucher in one tenant. */
    public VoucherReport vouchersForTenant(UUID tenantId, Voucher.Status status,
                                           LocalDate from, LocalDate to, Pageable pageable) {
        return voucherReport("TENANT", tenantId, tenantName(tenantId),
                tenantId, null, null, null, status, from, to, pageable);
    }

    /** Vouchers under one merchant. Guarded: a merchant in another tenant throws
     *  CROSS_TENANT (403) before any row is read. */
    /** One merchant's vouchers with the optional filters (the path's merchant wins
     *  over a {@code merchantId} filter; a {@code shopId} filter narrows within it). */
    public VoucherReport vouchersForMerchant(UUID tenantId, UUID merchantId, Voucher.Status status,
                                             LocalDate from, LocalDate to, VoucherReportFilters filters,
                                             Pageable pageable) {
        Merchant m = merchantService.requireMerchant(tenantId, merchantId);
        VoucherReportFilters f = filters == null ? VoucherReportFilters.none() : filters;
        return voucherReport("MERCHANT", merchantId, m.getName(),
                tenantId, null, merchantId, f.shopId(), null, status, from, to, pageable, f);
    }

    public VoucherReport vouchersForMerchant(UUID tenantId, UUID merchantId, Voucher.Status status,
                                             LocalDate from, LocalDate to, Pageable pageable) {
        Merchant m = merchantService.requireMerchant(tenantId, merchantId);
        return voucherReport("MERCHANT", merchantId, m.getName(),
                tenantId, null, merchantId, null, status, from, to, pageable);
    }

    /** Vouchers issued from one outlet. Guarded via ShopService.requireShop. */
    /** One outlet's vouchers with the optional filters (the path's shop wins). */
    public VoucherReport vouchersForShop(UUID tenantId, UUID shopId, Voucher.Status status,
                                         LocalDate from, LocalDate to, VoucherReportFilters filters,
                                         Pageable pageable) {
        Shop sh = shopService.requireShop(tenantId, shopId);
        return voucherReport("SHOP", shopId, sh.getName(),
                tenantId, null, null, shopId, null, status, from, to, pageable, filters);
    }

    public VoucherReport vouchersForShop(UUID tenantId, UUID shopId, Voucher.Status status,
                                         LocalDate from, LocalDate to, Pageable pageable) {
        Shop s = shopService.requireShop(tenantId, shopId);
        return voucherReport("SHOP", shopId, s.getName(),
                tenantId, null, null, shopId, status, from, to, pageable);
    }

    /** Full detail for one voucher, including its complete redemption log.
     *  Tenant-guarded — a voucher in another tenant throws CROSS_TENANT (403). */
    public VoucherDetail voucherDetail(UUID tenantId, UUID voucherId) {
        Voucher v = vouchers.findById(voucherId)
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        if (!v.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "voucher belongs to a different tenant");
        }
        return detailOf(v);
    }

    /**
     * The console's "Voucher lookup": one box that takes either the voucher id
     * or its code. A UUID is looked up by id, exactly as before (a foreign
     * tenant's id is still 403 CROSS_TENANT). Anything else is read as a typed
     * code through the same normalisation redemption uses (spaces, hyphens and
     * case are forgiven), and a code that exists only in ANOTHER tenant is a
     * plain 404: a staff lookup must not confirm that a code is real elsewhere.
     */
    /**
     * {@link #voucherDetail(UUID, String)} for a caller limited to some merchants
     * ({@code null} = every merchant). A sibling merchant's voucher is refused
     * like a foreign tenant's: by id 403 {@code NOT_MERCHANT_OWNER}; by code the
     * plain 404, so a typed code never confirms it exists elsewhere.
     */
    public VoucherDetail voucherDetail(UUID tenantId, String idOrCode, Set<UUID> merchantScope) {
        if (merchantScope == null) {
            return voucherDetail(tenantId, idOrCode);
        }
        if (idOrCode == null || idOrCode.isBlank()) {
            throw LoyaltyException.badRequest("VOUCHER_ID_OR_CODE_REQUIRED",
                    "Enter a voucher id or a voucher code.");
        }
        UUID id = parseUuid(idOrCode.strip());
        if (id != null) {
            Voucher v = vouchers.findById(id).orElseThrow(() -> LoyaltyException.notFound("voucher"));
            if (!v.getTenantId().equals(tenantId)) {
                throw LoyaltyException.forbidden("CROSS_TENANT", "voucher belongs to a different tenant");
            }
            if (!merchantScope.contains(v.getMerchantId())) {
                throw LoyaltyException.forbidden("NOT_MERCHANT_OWNER",
                        "You can only act on merchants you administer.");
            }
            return detailOf(v);
        }
        Voucher v = VoucherService.findByTypedCode(vouchers, idOrCode)
                .filter(found -> found.getTenantId().equals(tenantId))
                .filter(found -> merchantScope.contains(found.getMerchantId()))
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        return detailOf(v);
    }

    public VoucherDetail voucherDetail(UUID tenantId, String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw LoyaltyException.badRequest("VOUCHER_ID_OR_CODE_REQUIRED",
                    "Enter a voucher id or a voucher code.");
        }
        UUID id = parseUuid(idOrCode.strip());
        if (id != null) {
            return voucherDetail(tenantId, id);
        }
        Voucher v = VoucherService.findByTypedCode(vouchers, idOrCode)
                .filter(found -> found.getTenantId().equals(tenantId))
                .orElseThrow(() -> LoyaltyException.notFound("voucher"));
        return detailOf(v);
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private VoucherDetail detailOf(Voucher v) {
        UUID voucherId = v.getId();
        List<RedemptionDetail> reds = voucherRedemptions
                .findByVoucherIdOrderByRedeemedAtDesc(voucherId).stream()
                .map(ReportingService::toRedemption).toList();
        return toDetail(v,
                nameOf(v.getMerchantId(), id -> merchants.findById(id).map(Merchant::getName).orElse(null)),
                nameOf(v.getShopId(), id -> shops.findById(id).map(Shop::getName).orElse(null)),
                nameOf(v.getTemplateId(), id -> voucherTemplates.findById(id).map(VoucherTemplate::getName).orElse(null)),
                reds.size(), reds, purchaseInfo(List.of(voucherId)).get(voucherId));
    }

    private VoucherReport voucherReport(String level, UUID scopeId, String scopeName,
                                        UUID tenantId, UUID excludeTenantId, UUID merchantId, UUID shopId,
                                        Voucher.Status status, LocalDate from, LocalDate to, Pageable pageable) {
        return voucherReport(level, scopeId, scopeName, tenantId, excludeTenantId, merchantId, shopId,
                null, status, from, to, pageable, null);
    }

    private VoucherReport voucherReport(String level, UUID scopeId, String scopeName,
                                        UUID tenantId, UUID excludeTenantId, UUID merchantId, UUID shopId,
                                        Set<UUID> merchantScope,
                                        Voucher.Status status, LocalDate from, LocalDate to, Pageable pageable) {
        return voucherReport(level, scopeId, scopeName, tenantId, excludeTenantId, merchantId, shopId,
                merchantScope, status, from, to, pageable, null);
    }

    /** @param merchantScope the merchants a scoped caller may read ({@code null}
     *        = no narrowing; empty = nothing) — see {@code MerchantAuthz.readableMerchants} */
    private VoucherReport voucherReport(String level, UUID scopeId, String scopeName,
                                        UUID tenantId, UUID excludeTenantId, UUID merchantId, UUID shopId,
                                        Set<UUID> merchantScope,
                                        Voucher.Status status, LocalDate from, LocalDate to, Pageable pageable,
                                        VoucherReportFilters filters) {
        Instant fromI = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.EPOCH;
        Instant toI = to != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                : Instant.now().plus(1, ChronoUnit.DAYS);
        if (fromI.isAfter(toI)) {
            throw LoyaltyException.badRequest("RANGE_INVERTED", "from must not be after to");
        }
        boolean filtered = filters != null && !filters.isEmpty();
        // Filtered: the summary comes from the same Specification as the rows
        // (minus status, so the tabs keep counting the other statuses).
        VoucherSummary summary = summarise(filtered
                ? vouchers.summaryByStatus(filter(tenantId, excludeTenantId, merchantId, shopId, null, fromI, toI)
                        .and(inMerchants(merchantScope)).and(reportFilters(filters)))
                : merchantScope == null
                ? vouchers.reportSummaryByStatus(tenantId, excludeTenantId, merchantId, shopId, fromI, toI)
                : merchantScope.isEmpty() ? List.of()
                : vouchers.reportSummaryByStatusForMerchants(tenantId, merchantScope, fromI, toI));
        Specification<Voucher> spec = filter(tenantId, excludeTenantId, merchantId, shopId, status, fromI, toI)
                .and(inMerchants(merchantScope)).and(reportFilters(filters));
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "issuedAt"));
        Page<VoucherDetail> details = enrich(vouchers.findAll(spec, effective));
        return new VoucherReport(level, scopeId, scopeName, fromI, toI, summary, PageResponse.from(details));
    }

    /**
     * The optional report filters as one Specification ({@code null} or empty =
     * no narrowing). Every predicate is appended only when its filter is set —
     * never a nullable bind. Text matches escape LIKE wildcards, so a {@code %}
     * typed into the search box is literal.
     */
    private Specification<Voucher> reportFilters(VoucherReportFilters f) {
        return (root, query, cb) -> {
            if (f == null || f.isEmpty()) return cb.conjunction();
            List<Predicate> p = new ArrayList<>();
            if (f.merchantId() != null) p.add(cb.equal(root.get("merchantId"), f.merchantId()));
            if (f.shopId() != null) p.add(cb.equal(root.get("shopId"), f.shopId()));
            if (notBlank(f.currency())) {
                p.add(cb.equal(root.get("currency"), f.currency().strip().toUpperCase(java.util.Locale.ROOT)));
            }
            if (f.paymentMethod() != null) {
                p.add(paymentMethodPredicate(f.paymentMethod(), root, query, cb));
            }
            if (notBlank(f.issuedBy())) {
                String term = f.issuedBy().strip().toLowerCase(java.util.Locale.ROOT);
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("issuerEmail")), contains(term), '\\'));
                String digits = term.replaceAll("[^0-9]", "");
                if (digits.length() >= 4) any.add(cb.like(root.get("issuerPhone"), contains(digits), '\\'));
                p.add(cb.or(any.toArray(new Predicate[0])));
            }
            if (notBlank(f.phone())) {
                String digits = f.phone().replaceAll("[^0-9]", "");
                if (digits.isEmpty()) {
                    p.add(cb.disjunction());
                } else {
                    // The national number, so 0777… and +263777… match each other
                    // (and sender phones stored as typed before V56).
                    String tail = digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
                    p.add(cb.or(cb.like(root.get("assigneePhone"), endsWith(tail), '\\'),
                            cb.like(root.get("senderPhone"), endsWith(tail), '\\')));
                }
            }
            if (notBlank(f.q())) {
                String term = f.q().strip().toLowerCase(java.util.Locale.ROOT);
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("assigneeName")), contains(term), '\\'));
                any.add(cb.like(cb.lower(root.get("senderName")), contains(term), '\\'));
                any.add(cb.like(cb.lower(root.get("issuerEmail")), contains(term), '\\'));
                String code = term.replaceAll("[^0-9a-z]", "").toUpperCase(java.util.Locale.ROOT);
                if (code.length() >= 4) any.add(cb.like(root.get("code"), contains(code), '\\'));
                String digits = term.replaceAll("[^0-9]", "");
                if (digits.length() >= 4) {
                    any.add(cb.like(root.get("assigneePhone"), contains(digits), '\\'));
                    any.add(cb.like(root.get("senderPhone"), contains(digits), '\\'));
                }
                p.add(cb.or(any.toArray(new Predicate[0])));
            }
            if (f.bulk() != null) {
                p.add(f.bulk() ? cb.isNotNull(root.get("batchId")) : cb.isNull(root.get("batchId")));
            }
            if (f.batchId() != null) p.add(cb.equal(root.get("batchId"), f.batchId()));
            if (notBlank(f.campaign())) p.add(cb.equal(root.get("campaignSource"), f.campaign().strip()));
            if (f.expiresFrom() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<Instant>get("expiresAt"), startOfDay(f.expiresFrom())));
            }
            if (f.expiresTo() != null) {
                p.add(cb.lessThan(root.<Instant>get("expiresAt"), startOfDay(f.expiresTo().plusDays(1))));
            }
            if (f.redeemedFrom() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<Instant>get("redeemedAt"), startOfDay(f.redeemedFrom())));
            }
            if (f.redeemedTo() != null) {
                p.add(cb.lessThan(root.<Instant>get("redeemedAt"), startOfDay(f.redeemedTo().plusDays(1))));
            }
            if (f.minValue() != null) p.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("value"), f.minValue()));
            if (f.maxValue() != null) p.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("value"), f.maxValue()));
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    /** FREE = no purchase order behind the voucher; anything else = a PAID order
     *  of that kind (EXISTS, correlated — a NOT IN over a nullable column would
     *  silently match nothing). */
    private static Predicate paymentMethodPredicate(VoucherReportFilters.PaymentMethod method,
                                                    jakarta.persistence.criteria.Root<Voucher> root,
                                                    jakarta.persistence.criteria.CriteriaQuery<?> query,
                                                    jakarta.persistence.criteria.CriteriaBuilder cb) {
        var sq = query.subquery(UUID.class);
        var o = sq.from(com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.class);
        List<Predicate> w = new ArrayList<>();
        w.add(cb.equal(o.get("voucherId"), root.get("id")));
        switch (method) {
            case FREE -> { }
            case CASH -> w.add(cb.equal(o.get("paidVia"),
                    com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.PaidVia.CASH));
            case CARD_POS -> w.add(cb.equal(o.get("paidVia"),
                    com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.PaidVia.CARD_POS));
            case ONLINE -> w.add(cb.equal(o.get("paidVia"),
                    com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.PaidVia.GATEWAY));
            case INNBUCKS, ECOCASH, ONLINE_CARD -> {
                w.add(cb.equal(o.get("paidVia"),
                        com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder.PaidVia.GATEWAY));
                w.add(cb.equal(o.get("paymentRail"), switch (method) {
                    case INNBUCKS -> "INNBUCKS_CODE";
                    case ECOCASH -> "ECOCASH";
                    default -> "ZIMSWITCH_CARD";
                }));
            }
        }
        sq.select(o.get("id")).where(w.toArray(new Predicate[0]));
        return method == VoucherReportFilters.PaymentMethod.FREE ? cb.not(cb.exists(sq)) : cb.exists(sq);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Instant startOfDay(LocalDate d) {
        return d.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String contains(String s) {
        return "%" + escapeLike(s) + "%";
    }

    private static String endsWith(String s) {
        return "%" + escapeLike(s);
    }

    /** Narrows to a merchant scope: {@code null} = no narrowing, empty = no rows. */
    private static Specification<Voucher> inMerchants(Set<UUID> merchantScope) {
        return (root, query, cb) -> merchantScope == null ? cb.conjunction()
                : merchantScope.isEmpty() ? cb.disjunction()
                : root.get("merchantId").in(merchantScope);
    }

    /** Null-aware filter shared by every report level + the CSV export. */
    private static Specification<Voucher> filter(UUID tenantId, UUID excludeTenantId, UUID merchantId,
                                                 UUID shopId, Voucher.Status status, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (tenantId != null) p.add(cb.equal(root.get("tenantId"), tenantId));
            if (excludeTenantId != null) p.add(cb.notEqual(root.get("tenantId"), excludeTenantId));
            if (merchantId != null) p.add(cb.equal(root.get("merchantId"), merchantId));
            if (shopId != null) p.add(cb.equal(root.get("shopId"), shopId));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            p.add(cb.greaterThanOrEqualTo(root.<Instant>get("issuedAt"), from));
            p.add(cb.lessThan(root.<Instant>get("issuedAt"), to));
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private Page<VoucherDetail> enrich(Page<Voucher> page) {
        List<Voucher> content = page.getContent();
        Map<UUID, String> mNames = bulkNames(idset(content, Voucher::getMerchantId),
                ids -> merchants.findAllById(ids), Merchant::getId, Merchant::getName);
        Map<UUID, String> sNames = bulkNames(idset(content, Voucher::getShopId),
                ids -> shops.findAllById(ids), Shop::getId, Shop::getName);
        Map<UUID, String> tNames = bulkNames(idset(content, Voucher::getTemplateId),
                ids -> voucherTemplates.findAllById(ids), VoucherTemplate::getId, VoucherTemplate::getName);
        List<UUID> ids = content.stream().map(Voucher::getId).toList();
        Map<UUID, Long> redCounts = redemptionCounts(ids);
        Map<UUID, PurchaseInfo> purchases = purchaseInfo(ids);
        return page.map(v -> toDetail(v,
                mNames.get(v.getMerchantId()), sNames.get(v.getShopId()), tNames.get(v.getTemplateId()),
                redCounts.getOrDefault(v.getId(), 0L), null, purchases.get(v.getId())));
    }

    private Map<UUID, Long> redemptionCounts(List<UUID> voucherIds) {
        Map<UUID, Long> out = new HashMap<>();
        if (voucherIds.isEmpty()) return out;
        for (Object[] row : voucherRedemptions.countByVoucherIdIn(voucherIds)) {
            out.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    private static Set<UUID> idset(List<Voucher> vs, java.util.function.Function<Voucher, UUID> f) {
        return vs.stream().map(f).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }

    private static <E> Map<UUID, String> bulkNames(Set<UUID> ids,
                                                   java.util.function.Function<Set<UUID>, List<E>> fetch,
                                                   java.util.function.Function<E, UUID> idOf,
                                                   java.util.function.Function<E, String> nameOf) {
        Map<UUID, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (E e : fetch.apply(ids)) m.put(idOf.apply(e), nameOf.apply(e));
        return m;
    }

    private static String nameOf(UUID id, java.util.function.Function<UUID, String> resolver) {
        return id == null ? null : resolver.apply(id);
    }

    private static VoucherSummary summarise(List<Object[]> rows) {
        Map<String, Long> countByStatus = new LinkedHashMap<>();
        Map<String, BigDecimal> valueByStatus = new LinkedHashMap<>();
        long total = 0, outstanding = 0;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Object[] r : rows) {
            Voucher.Status st = (Voucher.Status) r[0];
            long c = ((Number) r[1]).longValue();
            BigDecimal val = toBigDecimal(r[2]);
            countByStatus.put(st.name(), c);
            valueByStatus.put(st.name(), val);
            total += c;
            totalValue = totalValue.add(val);
            if (OUTSTANDING.contains(st)) outstanding += c;
        }
        long redeemed = countByStatus.getOrDefault(Voucher.Status.REDEEMED.name(), 0L);
        BigDecimal redeemedValue = valueByStatus.getOrDefault(Voucher.Status.REDEEMED.name(), BigDecimal.ZERO);
        long expired = countByStatus.getOrDefault(Voucher.Status.EXPIRED.name(), 0L);
        long revoked = countByStatus.getOrDefault(Voucher.Status.REVOKED.name(), 0L);
        double rate = total > 0 ? Math.round((redeemed * 10000.0) / total) / 100.0 : 0.0;
        return new VoucherSummary(total, countByStatus, valueByStatus, totalValue,
                redeemed, redeemedValue, outstanding, expired, revoked, rate);
    }

    private static VoucherDetail toDetail(Voucher v, String merchantName, String shopName, String templateName,
                                          long redemptionCount, List<RedemptionDetail> redemptions) {
        return toDetail(v, merchantName, shopName, templateName, redemptionCount, redemptions, null);
    }

    private static VoucherDetail toDetail(Voucher v, String merchantName, String shopName, String templateName,
                                          long redemptionCount, List<RedemptionDetail> redemptions,
                                          PurchaseInfo purchase) {
        boolean expired = v.getExpiresAt() != null
                && v.getExpiresAt().isBefore(Instant.now())
                && v.getStatus() != Voucher.Status.REDEEMED
                && v.getStatus() != Voucher.Status.REVOKED
                && v.getStatus() != Voucher.Status.EXPIRED;
        return new VoucherDetail(
                v.getId(), v.getCode(), v.getStatus() == null ? null : v.getStatus().name(),
                v.getTenantId(),
                v.getMerchantId(), merchantName,
                v.getShopId(), shopName,
                v.getTemplateId(), templateName,
                v.getBatchId(),
                v.getIssuerUserId(), v.getIssuerPhone(), v.getIssuerEmail(),
                v.getAssignedUserId(), v.getAssigneePhone(), v.getAssigneeName(),
                v.getVoucherType() == null ? null : v.getVoucherType().name(),
                v.getValue(), v.getCurrency(), v.getUsesRemaining(),
                v.getDeliveryChannel() == null ? null : v.getDeliveryChannel().name(),
                v.getCampaignSource(),
                v.getIssuedAt(), v.getDeliveredAt(), v.getViewedAt(), v.getRedeemedAt(), v.getExpiresAt(),
                expired,
                v.getSenderName(), v.getSenderPhone(),
                v.getTransferredAt(), v.getTransferredFromUserId(), v.getTransferredFromPhone(),
                redemptionCount, redemptions,
                (purchase == null ? VoucherReportFilters.PaymentMethod.FREE : purchase.method()).name(),
                purchase == null ? null : purchase.orderRef());
    }

    /** How one voucher was paid for, read from its purchase order. */
    private record PurchaseInfo(String orderRef, VoucherReportFilters.PaymentMethod method) {}

    /** The report's payment method for a paid order: the rail when known
     *  (recorded from V56), else ONLINE for an electronic payment. */
    static VoucherReportFilters.PaymentMethod methodOf(Object paidVia, Object rail) {
        String via = paidVia == null ? null : paidVia.toString();
        if ("CASH".equals(via)) return VoucherReportFilters.PaymentMethod.CASH;
        if ("CARD_POS".equals(via)) return VoucherReportFilters.PaymentMethod.CARD_POS;
        String r = rail == null ? "" : rail.toString();
        return switch (r) {
            case "INNBUCKS_CODE" -> VoucherReportFilters.PaymentMethod.INNBUCKS;
            case "ECOCASH" -> VoucherReportFilters.PaymentMethod.ECOCASH;
            case "ZIMSWITCH_CARD" -> VoucherReportFilters.PaymentMethod.ONLINE_CARD;
            default -> VoucherReportFilters.PaymentMethod.ONLINE;
        };
    }

    /** One query per page: the purchase order behind each voucher, if any. */
    private Map<UUID, PurchaseInfo> purchaseInfo(List<UUID> voucherIds) {
        Map<UUID, PurchaseInfo> out = new HashMap<>();
        if (voucherIds.isEmpty()) return out;
        for (Object[] row : vouchers.purchaseInfoForVouchers(voucherIds)) {
            if (row[0] == null || row[2] == null) continue;   // an unpaid order carries no voucher
            out.put((UUID) row[0], new PurchaseInfo((String) row[1], methodOf(row[2], row[3])));
        }
        return out;
    }

    private static RedemptionDetail toRedemption(VoucherRedemption r) {
        return new RedemptionDetail(r.getId(), r.getRedeemedAt(),
                r.getResult() == null ? null : r.getResult().name(),
                r.getMerchantId(), r.getOutletCode(), r.getUserId(),
                r.getIpAddress(), r.getDeviceFingerprint(), r.getReason());
    }

    private String tenantName(UUID tenantId) {
        return tenants.findById(tenantId).map(t -> t.getName()).orElse(null);
    }

    /**
     * Header of the voucher CSV export. The endpoint's own Swagger says "same
     * columns as VoucherDetail", so this must carry every component of
     * {@link VoucherDetail} except {@code redemptions} (a nested list, which has
     * no sensible flat column) — a field added to that record and not to this
     * string is a column silently missing from every operator's export.
     * {@code VoucherCsvHeaderTest} enforces exactly that, by SET rather than by
     * order: the order here deliberately differs, because new columns are
     * APPENDED after {@code redemptionCount} so a positional parser written
     * against the old export keeps working.
     */
    static final String VOUCHER_CSV_HEADER =
            "id,code,status,tenantId,merchantId,merchantName,shopId,shopName,templateId,templateName,batchId,"
                    + "issuerUserId,issuerPhone,issuerEmail,receiverUserId,receiverPhone,receiverName,"
                    + "voucherType,faceValue,currency,usesRemaining,deliveryChannel,campaignSource,"
                    + "issuedAt,deliveredAt,viewedAt,redeemedAt,expiresAt,expired,redemptionCount,"
                    + "senderName,senderPhone,transferredAt,transferredFromUserId,transferredFromPhone,"
                    + "paymentMethod,orderRef\n";

    /**
     * CSV export — one fully-detailed row per voucher. {@code level} selects the
     * scope and applies the SAME tenant/merchant/shop guard as the JSON reports;
     * pages the DB at 500 rows so a busy period doesn't materialise everything.
     */
    public String voucherCsv(String level, UUID tenantId, UUID scopeId,
                             Voucher.Status status, LocalDate from, LocalDate to) {
        return voucherCsv(level, tenantId, scopeId, null, status, from, to);
    }

    /** @param merchantScope narrows the TENANT level to the merchants a scoped
     *        caller may read ({@code null} = no narrowing); the MERCHANT / SHOP
     *        levels are ownership-checked by the controller instead. */
    public String voucherCsv(String level, UUID tenantId, UUID scopeId, Set<UUID> merchantScope,
                             Voucher.Status status, LocalDate from, LocalDate to) {
        return voucherCsv(level, tenantId, scopeId, merchantScope, status, from, to, null);
    }

    /** The CSV export with the same optional filters as the JSON reports. */
    public String voucherCsv(String level, UUID tenantId, UUID scopeId, Set<UUID> merchantScope,
                             Voucher.Status status, LocalDate from, LocalDate to,
                             VoucherReportFilters filters) {
        try (java.io.StringWriter out = new java.io.StringWriter()) {
            writeVoucherCsv(out, level, tenantId, scopeId, merchantScope, status, from, to, filters);
            return out.toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e); // StringWriter.close() never throws
        }
    }

    /**
     * The voucher export, written to {@code out} a page at a time. As with
     * {@link #writeCsv}, every refusal (the scope, the named merchant or shop,
     * an inverted range) is raised before the first write.
     */
    public void writeVoucherCsv(java.io.Writer out, String level, UUID tenantId, UUID scopeId,
                                Set<UUID> merchantScope, Voucher.Status status, LocalDate from, LocalDate to,
                                VoucherReportFilters filters) {
        UUID excludeTenantId = null, filterTenantId = null, merchantId = null, shopId = null;
        switch (level == null ? "" : level.toUpperCase()) {
            case "OPERATOR" -> excludeTenantId = TicketingLoyaltyService.TICKETING_TENANT_ID;
            case "TENANT" -> filterTenantId = requireScopeTenant(tenantId);
            case "MERCHANT" -> {
                filterTenantId = requireScopeTenant(tenantId);
                merchantService.requireMerchant(filterTenantId, scopeId);
                merchantId = scopeId;
            }
            case "SHOP" -> {
                filterTenantId = requireScopeTenant(tenantId);
                shopService.requireShop(filterTenantId, scopeId);
                shopId = scopeId;
            }
            default -> throw LoyaltyException.badRequest("BAD_SCOPE",
                    "scope must be one of operator, tenant, merchant, shop");
        }
        Instant fromI = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.EPOCH;
        Instant toI = to != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                : Instant.now().plus(1, ChronoUnit.DAYS);
        if (fromI.isAfter(toI)) throw LoyaltyException.badRequest("RANGE_INVERTED", "from must not be after to");

        // The level's own merchant/shop wins; otherwise a filter may name one.
        if (filters != null) {
            if (merchantId == null) merchantId = filters.merchantId();
            if (shopId == null) shopId = filters.shopId();
        }
        Specification<Voucher> spec = filter(filterTenantId, excludeTenantId, merchantId, shopId, status, fromI, toI)
                .and(inMerchants(merchantScope)).and(reportFilters(filters));
        write(out, VOUCHER_CSV_HEADER);
        int pageNum = 0;
        int pageSize = 500;
        while (true) {
            // id breaks ties: offset paging over a non-unique sort key can repeat
            // or skip a row at a page boundary (a bulk issue stamps many vouchers
            // with the same issuedAt).
            Page<Voucher> page = vouchers.findAll(spec,
                    PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "issuedAt")
                            .and(Sort.by(Sort.Direction.DESC, "id"))));
            StringBuilder sb = new StringBuilder();
            for (VoucherDetail d : enrich(page).getContent()) {
                sb.append(csvField(d.id())).append(',')
                        // Grouped with hyphens HERE only — never in toDetail, which also
                        // feeds the JSON report, where the code must stay raw. A raw
                        // 16-digit code opened in a spreadsheet loses its last digit.
                        .append(csvField(VoucherCodes.forExport(d.code()))).append(',')
                        .append(csvField(d.status())).append(',')
                        .append(csvField(d.tenantId())).append(',')
                        .append(csvField(d.merchantId())).append(',')
                        .append(csvField(d.merchantName())).append(',')
                        .append(csvField(d.shopId())).append(',')
                        .append(csvField(d.shopName())).append(',')
                        .append(csvField(d.templateId())).append(',')
                        .append(csvField(d.templateName())).append(',')
                        .append(csvField(d.batchId())).append(',')
                        .append(csvField(d.issuerUserId())).append(',')
                        .append(csvField(d.issuerPhone())).append(',')
                        .append(csvField(d.issuerEmail())).append(',')
                        .append(csvField(d.receiverUserId())).append(',')
                        .append(csvField(d.receiverPhone())).append(',')
                        .append(csvField(d.receiverName())).append(',')
                        .append(csvField(d.voucherType())).append(',')
                        .append(csvField(d.faceValue())).append(',')
                        .append(csvField(d.currency())).append(',')
                        .append(d.usesRemaining()).append(',')
                        .append(csvField(d.deliveryChannel())).append(',')
                        .append(csvField(d.campaignSource())).append(',')
                        .append(csvField(d.issuedAt())).append(',')
                        .append(csvField(d.deliveredAt())).append(',')
                        .append(csvField(d.viewedAt())).append(',')
                        .append(csvField(d.redeemedAt())).append(',')
                        .append(csvField(d.expiresAt())).append(',')
                        .append(d.expired()).append(',')
                        .append(d.redemptionCount()).append(',')
                        .append(csvField(d.senderName())).append(',')
                        .append(csvField(d.senderPhone())).append(',')
                        .append(csvField(d.transferredAt())).append(',')
                        .append(csvField(d.transferredFromUserId())).append(',')
                        .append(csvField(d.transferredFromPhone())).append(',')
                        .append(csvField(d.paymentMethod())).append(',')
                        .append(csvField(d.orderRef()))
                        .append('\n');
            }
            write(out, sb);
            boolean last = page.isLast();
            detachPage();
            if (last) break;
            pageNum++;
        }
    }

    private static UUID requireScopeTenant(UUID tenantId) {
        if (tenantId == null) {
            throw LoyaltyException.badRequest("TENANT_REQUIRED", "X-Tenant-Id header is required for this scope");
        }
        return tenantId;
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw LoyaltyException.badRequest("RANGE_REQUIRED", "from and to are required");
        }
        if (from.isAfter(to)) {
            throw LoyaltyException.badRequest("RANGE_INVERTED", "from must not be after to");
        }
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }

    private static Instant toInstantOrNull(Object o) {
        return o == null ? null : toInstantUtc(o);
    }

    private static Instant toInstantUtc(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.util.Date d) return d.toInstant();
        // Defensive default — at the time of writing only the three types
        // above are produced by Hibernate for date_trunc; falls through to
        // string parse as a last resort.
        return Instant.parse(o.toString());
    }

    private static String csvField(Object o) {
        if (o == null) return "";
        String s = o.toString();
        // Neutralise spreadsheet formula injection: a cell beginning with
        // = + - @ (or a tab/CR) is executed as a formula by Excel/Sheets, so an
        // attacker-controlled value (e.g. a transaction `reference`) could run
        // =HYPERLINK/DDE when an operator opens the export. Prefix a single quote
        // so it renders as literal text. See OWASP "CSV Injection".
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        // Quote whenever the field contains a structural character so a
        // comma in a reference (e.g. "POS-001,batch-A") doesn't shred the
        // row, and inline quotes are escaped per RFC 4180 (`"` -> `""`).
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    @SuppressWarnings("unused")
    private static long bucketsBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}
