package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public ReportingService(TenantRepository tenants, MerchantRepository merchants,
                            LoyaltyUserRepository users,
                            LoyaltyTransactionRepository transactions,
                            VoucherRepository vouchers,
                            InvoiceRepository invoices,
                            FraudAttemptRepository fraud,
                            CampaignRepository campaigns,
                            MerchantService merchantService,
                            UserService userService) {
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
    }

    public Dtos.OperatorDashboard operator() {
        Instant startOfDay = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = startOfDay.plusSeconds(86_400);
        Instant in7 = startOfDay.plusSeconds(7 * 86_400);
        Instant in30 = startOfDay.plusSeconds(30 * 86_400);
        Instant since24h = Instant.now().minusSeconds(86_400);

        long totalTenants = tenants.count();
        long activeMerchants = merchants.findAll().stream()
                .filter(m -> m.getStatus() == Merchant.Status.ACTIVE).count();
        long txnsToday = transactions.countSince(startOfDay);

        BigDecimal pointsIssuedToday = BigDecimal.ZERO;
        BigDecimal pointsRedeemedToday = BigDecimal.ZERO;
        long vouchersIssuedToday = 0;
        long vouchersRedeemedToday = 0;
        for (Merchant m : merchants.findAll()) {
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
        LocalDate nextInvoice = m == null ? null
                : (m.getBillingCycle() == Merchant.BillingCycle.WEEKLY
                    ? LocalDate.now().plusWeeks(1).with(java.time.DayOfWeek.MONDAY)
                    : LocalDate.now().withDayOfMonth(1).plusMonths(1));
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

    public Dtos.ShopPointsReport pointsForShop(UUID tenantId, UUID shopId, LocalDate from, LocalDate to) {
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
        return new Dtos.ShopPointsReport(shopId, from, to, issued, redeemed,
                issued.subtract(redeemed), count, byPhone);
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
