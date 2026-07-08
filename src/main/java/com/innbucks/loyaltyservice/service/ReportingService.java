package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.RedemptionDetail;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherDetail;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherReport;
import com.innbucks.loyaltyservice.dto.VoucherReportDtos.VoucherSummary;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
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
        List<Merchant> realMerchants = merchants.findAll().stream()
                .filter(m -> !ticketing.equals(m.getTenantId()))
                .toList();
        long activeMerchants = realMerchants.stream()
                .filter(m -> m.getStatus() == Merchant.Status.ACTIVE).count();
        long txnsToday = transactions.countSinceExcludingTenant(startOfDay, ticketing);

        BigDecimal pointsIssuedToday = BigDecimal.ZERO;
        BigDecimal pointsRedeemedToday = BigDecimal.ZERO;
        long vouchersIssuedToday = 0;
        long vouchersRedeemedToday = 0;
        for (Merchant m : realMerchants) {
            pointsIssuedToday = pointsIssuedToday.add(transactions.sumPointsIssued(m.getId(), startOfDay, endOfDay));
            pointsRedeemedToday = pointsRedeemedToday.add(transactions.sumPointsRedeemed(m.getId(), startOfDay, endOfDay));
            vouchersIssuedToday += vouchers.countByMerchantIdAndIssuedAtBetween(m.getId(), startOfDay, endOfDay);
            vouchersRedeemedToday += vouchers.countByMerchantIdAndRedeemedAtBetween(m.getId(), startOfDay, endOfDay);
        }

        long fraudAttempts = fraud.countByCreatedAtAfter(since24h);
        long invoicesPending = invoices.countByStatus(Invoice.Status.PENDING);
        long invoicesPaid = invoices.countByStatus(Invoice.Status.PAID);

        long expiringIn7 = vouchers.findExpired(in7).size();
        long expiringIn30 = vouchers.findExpired(in30).size();

        return new Dtos.OperatorDashboard(totalTenants, activeMerchants, txnsToday,
                vouchersIssuedToday, vouchersRedeemedToday,
                pointsIssuedToday, pointsRedeemedToday, fraudAttempts,
                invoicesPending, invoicesPaid, expiringIn7, expiringIn30);
    }

    public Dtos.TenantDashboard tenant(UUID tenantId) {
        long merchantCount = merchants.findByTenantId(tenantId).size();
        long activeCampaigns = campaigns.findByTenantId(tenantId).stream()
                .filter(Campaign::isActive).count();
        long outstanding = vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.ISSUED)
                + vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.DELIVERED)
                + vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.VIEWED)
                + vouchers.countByTenantIdAndStatus(tenantId, Voucher.Status.PARTIALLY_USED);
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
        BigDecimal estimatedInvoice = m == null ? BigDecimal.ZERO
                : issuedVouchers.stream()
                        .map(v -> MerchantFeeCalculator.feeForIssued(m, v))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .add(redeemedVouchers.stream()
                        .map(v -> MerchantFeeCalculator.feeForRedeemed(m, v))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
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
     *   <li>MERCHANT_ADMIN — only merchants whose {@code adminEmail} is theirs.</li>
     * </ul>
     * Rather than 403-ing, out-of-scope merchants are simply absent — the list is
     * "everything you administer", whoever asks.
     *
     * <p>Pagination slices AFTER the visibility filter (name-ordered) so page
     * numbers are stable per caller. Tenant-wide rules + campaigns are fetched
     * once and reused across every merchant on the page.
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
        List<Dtos.MerchantFullReport> content = visible.subList(start, end).stream()
                .map(m -> buildMerchantFullReport(m, tenantRules, tenantCampaigns))
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
        String callerEmail = CallerDetails.currentEmail();         // MERCHANT_ADMIN
        return Objects.requireNonNullElse(m.getAdminEmail(), "").equalsIgnoreCase(callerEmail);
    }

    private Dtos.MerchantFullReport buildMerchantFullReport(Merchant m,
                                                            List<LoyaltyRule> tenantRules,
                                                            List<Campaign> tenantCampaigns) {
        UUID id = m.getId();
        Instant now = Instant.now();
        Instant epoch = Instant.EPOCH;
        Instant thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);

        // Shops — every outlet under the merchant.
        List<Dtos.ShopResponse> shopList = shops.findByTenantIdAndMerchantId(m.getTenantId(), id).stream()
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

        // Points — lifetime activity.
        BigDecimal ptsIssued = nz(transactions.sumPointsIssued(id, epoch, now));
        BigDecimal ptsRedeemed = nz(transactions.sumPointsRedeemed(id, epoch, now));
        Map<String, Long> txnsByType = new TreeMap<>();
        for (Object[] row : transactions.countByType(m.getTenantId(), id, epoch, now)) {
            txnsByType.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Dtos.PointsSummary points = new Dtos.PointsSummary(
                ptsIssued, ptsRedeemed, ptsIssued.subtract(ptsRedeemed),
                transactions.countByMerchantIdAndCreatedAtBetween(id, epoch, now),
                txnsByType,
                transactions.findFirstByMerchantIdOrderByCreatedAtAsc(id)
                        .map(LoyaltyTransaction::getCreatedAt).orElse(null),
                transactions.findFirstByMerchantIdOrderByCreatedAtDesc(id)
                        .map(LoyaltyTransaction::getCreatedAt).orElse(null));

        // Vouchers — lifetime status breakdown + face values in ONE grouped query.
        Map<String, Long> vouchersByStatus = new TreeMap<>();
        long voucherTotal = 0;
        BigDecimal valueIssued = BigDecimal.ZERO;
        for (Object[] row : vouchers.reportSummaryByStatus(null, null, id, null, epoch, now)) {
            long count = ((Number) row[1]).longValue();
            vouchersByStatus.put(String.valueOf(row[0]), count);
            voucherTotal += count;
            valueIssued = valueIssued.add((BigDecimal) row[2]);
        }
        Dtos.VoucherSummary voucherSummary = new Dtos.VoucherSummary(
                voucherTotal, vouchersByStatus, valueIssued,
                nz(vouchers.sumRedeemedValueByMerchantId(id)),
                vouchers.countByMerchantIdAndIssuedAtBetween(id, thirtyDaysAgo, now),
                vouchers.countByMerchantIdAndRedeemedAtBetween(id, thirtyDaysAgo, now));

        // Invoices — full history rolled up, most recent 12 inlined.
        List<Invoice> invoiceRows = invoices.findByMerchantIdOrderByPeriodEndDesc(id);
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
        LocalDate currentPeriodStart = switch (m.getBillingCycle()) {
            case DAILY -> today;
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            case MONTHLY -> today.withDayOfMonth(1);
        };
        Instant periodFrom = currentPeriodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal estimatedFees = vouchers.findByMerchantIdAndIssuedAtBetween(id, periodFrom, now).stream()
                .map(v -> MerchantFeeCalculator.feeForIssued(m, v))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(vouchers.findByMerchantIdAndRedeemedAtBetween(id, periodFrom, now).stream()
                        .map(v -> MerchantFeeCalculator.feeForRedeemed(m, v))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        Dtos.InvoiceSummary invoiceSummary = new Dtos.InvoiceSummary(
                invoiceRows.size(), pending, paid, overdue, cancelled,
                billed, paidAmount, outstanding, nextInvoice, estimatedFees,
                invoiceRows.stream().limit(12).map(InvoicingService::toResponse).toList());

        // Headline stats.
        Dtos.MerchantStats stats = new Dtos.MerchantStats(
                shopList.size(),
                shopList.stream().filter(s -> s.status() == Shop.Status.ACTIVE).count(),
                transactions.countDistinctUsersByMerchantId(id),
                fraud.countByMerchantIdAndCreatedAtAfter(id, thirtyDaysAgo),
                campaignLines.stream().filter(Dtos.CampaignLine::active).count(),
                vouchers.countByMerchantIdAndExpiresAtBetweenAndStatusIn(id, now,
                        now.plus(30, ChronoUnit.DAYS),
                        List.of(Voucher.Status.ISSUED, Voucher.Status.DELIVERED,
                                Voucher.Status.VIEWED, Voucher.Status.PARTIALLY_USED)));

        return new Dtos.MerchantFullReport(id, m.getTenantId(), m.getName(), m.getCategory(),
                m.getCurrency(), m.getBillingCycle(), m.getStatus(), m.getAdminEmail(), m.getCreatedAt(),
                new Dtos.FeeModel(m.getFeeIssuedType(), m.getFeeIssuedFixed(), m.getFeeIssuedPercentage()),
                new Dtos.FeeModel(m.getFeeRedeemedType(), m.getFeeRedeemedFixed(), m.getFeeRedeemedPercentage()),
                shopList, ruleLines, campaignLines, points, voucherSummary, invoiceSummary, stats);
    }

    /** Mock/driver safety: aggregate queries COALESCE to 0, but a null must never NPE a report. */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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

    public List<Dtos.PointsByTypeRow> pointsByType(UUID tenantId, UUID merchantId,
                                                   LocalDate from, LocalDate to) {
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Object[]> rows = transactions.sumPointsByType(tenantId, merchantId, fromInstant, toInstant);
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

    public List<Dtos.PointsTimeSeriesPoint> pointsTimeSeries(UUID tenantId, UUID merchantId,
                                                             LocalDate from, LocalDate to) {
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        // The query skips zero-activity days; we backfill so the FE always
        // gets a contiguous series and can render a chart without holes.
        Map<Instant, Dtos.PointsTimeSeriesPoint> byBucket = new LinkedHashMap<>();
        for (Object[] r : transactions.dailyPointBuckets(tenantId, merchantId, fromInstant, toInstant)) {
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
    public String csv(UUID tenantId, UUID merchantId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        Instant fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        StringBuilder sb = new StringBuilder(
                "id,createdAt,type,amount,pointsDelta,merchantId,shopId,userId,reference\n");

        int pageSize = 500;
        int pageNum = 0;
        while (true) {
            var page = (merchantId == null)
                    ? transactions.findByTenantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                            tenantId, fromInstant, toInstant, PageRequest.of(pageNum, pageSize))
                    : transactions.findByTenantIdAndMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                            tenantId, merchantId, fromInstant, toInstant, PageRequest.of(pageNum, pageSize));
            for (LoyaltyTransaction t : page.getContent()) {
                sb.append(t.getId()).append(',')
                        .append(t.getCreatedAt()).append(',')
                        .append(t.getType()).append(',')
                        .append(csvField(t.getAmount())).append(',')
                        .append(csvField(t.getPointsDelta())).append(',')
                        .append(t.getMerchantId() == null ? "" : t.getMerchantId()).append(',')
                        .append(t.getShopId() == null ? "" : t.getShopId()).append(',')
                        .append(t.getUserId()).append(',')
                        .append(csvField(t.getReference()))
                        .append('\n');
            }
            if (page.isLast()) break;
            pageNum++;
        }
        return sb.toString();
    }

    // ==================================================================
    // Detailed voucher reports — operator / tenant / merchant / shop /
    // single-voucher, plus CSV export. See VoucherReportDtos.
    // ==================================================================

    private static final Set<Voucher.Status> OUTSTANDING = EnumSet.of(
            Voucher.Status.ISSUED, Voucher.Status.DELIVERED,
            Voucher.Status.VIEWED, Voucher.Status.PARTIALLY_USED);

    /** Platform-wide voucher report across every real tenant. The internal
     *  ticketing container tenant is excluded, matching the operator dashboard. */
    public VoucherReport vouchersForOperator(Voucher.Status status, LocalDate from, LocalDate to, Pageable pageable) {
        return voucherReport("OPERATOR", null, null,
                null, TicketingLoyaltyService.TICKETING_TENANT_ID, null, null,
                status, from, to, pageable);
    }

    /** Every voucher in one tenant. */
    public VoucherReport vouchersForTenant(UUID tenantId, Voucher.Status status,
                                           LocalDate from, LocalDate to, Pageable pageable) {
        return voucherReport("TENANT", tenantId, tenantName(tenantId),
                tenantId, null, null, null, status, from, to, pageable);
    }

    /** Vouchers under one merchant. Guarded: a merchant in another tenant throws
     *  CROSS_TENANT (403) before any row is read. */
    public VoucherReport vouchersForMerchant(UUID tenantId, UUID merchantId, Voucher.Status status,
                                             LocalDate from, LocalDate to, Pageable pageable) {
        Merchant m = merchantService.requireMerchant(tenantId, merchantId);
        return voucherReport("MERCHANT", merchantId, m.getName(),
                tenantId, null, merchantId, null, status, from, to, pageable);
    }

    /** Vouchers issued from one outlet. Guarded via ShopService.requireShop. */
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
        List<RedemptionDetail> reds = voucherRedemptions
                .findByVoucherIdOrderByRedeemedAtDesc(voucherId).stream()
                .map(ReportingService::toRedemption).toList();
        return toDetail(v,
                nameOf(v.getMerchantId(), id -> merchants.findById(id).map(Merchant::getName).orElse(null)),
                nameOf(v.getShopId(), id -> shops.findById(id).map(Shop::getName).orElse(null)),
                nameOf(v.getTemplateId(), id -> voucherTemplates.findById(id).map(VoucherTemplate::getName).orElse(null)),
                reds.size(), reds);
    }

    private VoucherReport voucherReport(String level, UUID scopeId, String scopeName,
                                        UUID tenantId, UUID excludeTenantId, UUID merchantId, UUID shopId,
                                        Voucher.Status status, LocalDate from, LocalDate to, Pageable pageable) {
        Instant fromI = from != null ? from.atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.EPOCH;
        Instant toI = to != null ? to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                : Instant.now().plus(1, ChronoUnit.DAYS);
        if (fromI.isAfter(toI)) {
            throw LoyaltyException.badRequest("RANGE_INVERTED", "from must not be after to");
        }
        VoucherSummary summary = summarise(
                vouchers.reportSummaryByStatus(tenantId, excludeTenantId, merchantId, shopId, fromI, toI));
        Specification<Voucher> spec = filter(tenantId, excludeTenantId, merchantId, shopId, status, fromI, toI);
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "issuedAt"));
        Page<VoucherDetail> details = enrich(vouchers.findAll(spec, effective));
        return new VoucherReport(level, scopeId, scopeName, fromI, toI, summary, PageResponse.from(details));
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
        Map<UUID, Long> redCounts = redemptionCounts(content.stream().map(Voucher::getId).toList());
        return page.map(v -> toDetail(v,
                mNames.get(v.getMerchantId()), sNames.get(v.getShopId()), tNames.get(v.getTemplateId()),
                redCounts.getOrDefault(v.getId(), 0L), null));
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
                v.getValueType() == null ? null : v.getValueType().name(),
                v.getValue(), v.getCurrency(), v.getUsesRemaining(),
                v.getDeliveryChannel() == null ? null : v.getDeliveryChannel().name(),
                v.getCampaignSource(),
                v.getIssuedAt(), v.getDeliveredAt(), v.getViewedAt(), v.getRedeemedAt(), v.getExpiresAt(),
                expired, redemptionCount, redemptions);
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
     * CSV export — one fully-detailed row per voucher. {@code level} selects the
     * scope and applies the SAME tenant/merchant/shop guard as the JSON reports;
     * pages the DB at 500 rows so a busy period doesn't materialise everything.
     */
    public String voucherCsv(String level, UUID tenantId, UUID scopeId,
                             Voucher.Status status, LocalDate from, LocalDate to) {
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

        Specification<Voucher> spec = filter(filterTenantId, excludeTenantId, merchantId, shopId, status, fromI, toI);
        StringBuilder sb = new StringBuilder(
                "id,code,status,tenantId,merchantId,merchantName,shopId,shopName,templateId,templateName,batchId,"
                        + "issuerUserId,issuerPhone,issuerEmail,receiverUserId,receiverPhone,receiverName,"
                        + "valueType,faceValue,currency,usesRemaining,deliveryChannel,campaignSource,"
                        + "issuedAt,deliveredAt,viewedAt,redeemedAt,expiresAt,expired,redemptionCount\n");
        int pageNum = 0;
        int pageSize = 500;
        while (true) {
            Page<Voucher> page = vouchers.findAll(spec,
                    PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "issuedAt")));
            for (VoucherDetail d : enrich(page).getContent()) {
                sb.append(csvField(d.id())).append(',')
                        .append(csvField(d.code())).append(',')
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
                        .append(csvField(d.valueType())).append(',')
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
                        .append(d.redemptionCount())
                        .append('\n');
            }
            if (page.isLast()) break;
            pageNum++;
        }
        return sb.toString();
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
