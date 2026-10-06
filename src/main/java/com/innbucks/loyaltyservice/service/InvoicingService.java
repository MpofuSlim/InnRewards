package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.integration.InvoiceGeneratedEvent;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class InvoicingService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(InvoicingService.class);

    private final InvoiceRepository invoices;
    private final MerchantRepository merchants;
    private final LoyaltyTransactionRepository transactions;
    private final VoucherRepository vouchers;
    private final LoyaltyRuleRepository rules;
    private final LoyaltyProperties props;
    private final com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz;
    private final ApplicationEventPublisher events;

    /** One merchant's invoice per transaction in the nightly run — see
     *  {@link #runPeriodicForAllMerchants}. */
    private final TransactionTemplate perMerchantTx;
    /** The run's batched pre-load: one read-only transaction, one connection. */
    private final TransactionTemplate preloadTx;

    /** Merchants whose invoice the nightly run could not write. Alert on any increase. */
    public static final String RUN_FAILED_METRIC = "loyalty.invoice.run.failed";
    private final Counter runFailures;

    public InvoicingService(InvoiceRepository invoices, MerchantRepository merchants,
                            LoyaltyTransactionRepository transactions,
                            VoucherRepository vouchers,
                            LoyaltyRuleRepository rules,
                            LoyaltyProperties props,
                            com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz,
                            ApplicationEventPublisher events,
                            PlatformTransactionManager transactionManager,
                            MeterRegistry meters) {
        this.invoices = invoices;
        this.merchants = merchants;
        this.transactions = transactions;
        this.vouchers = vouchers;
        this.rules = rules;
        this.props = props;
        this.merchantAuthz = merchantAuthz;
        this.events = events;
        this.perMerchantTx = new TransactionTemplate(transactionManager);
        this.perMerchantTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.preloadTx = new TransactionTemplate(transactionManager);
        this.preloadTx.setReadOnly(true);
        // Registered at construction so the series exists at 0 before the first
        // failure — increase() cannot see a series' first sample.
        this.runFailures = Counter.builder(RUN_FAILED_METRIC)
                .description("Merchants whose invoice the nightly invoice run failed to write "
                        + "(rolled back alone; the run continued with the other merchants)")
                .register(meters);
    }

    /**
     * Returns the new invoice, or {@link Optional#empty()} when the
     * merchant had no billable activity in the period (totalAmount = 0).
     * Skipping zero-total rows keeps the merchant's billing page clean
     * — a chain that didn't issue or redeem a single voucher all month
     * shouldn't see "INV-202605-0001 — $0.00" stacked alongside its
     * real bills, and ops shouldn't have to mark phantom invoices PAID
     * to dismiss them.
     */
    public Optional<Invoice> generate(Merchant m, LocalDate periodStart, LocalDate periodEnd) {
        invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(m.getId(), periodStart, periodEnd)
                .ifPresent(existing -> {
                    throw LoyaltyException.conflict("INVOICE_EXISTS",
                            "invoice already exists for this merchant + period");
                });
        Instant from = periodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = periodEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        // Pull individual vouchers (not just COUNT) so PERCENTAGE / FIXED_PLUS_
        // PERCENTAGE fees can multiply each row's face value by the merchant's
        // configured percentage. For FIXED, this still sums to count*flat —
        // MerchantFeeCalculator.compute returns the same value for every row.
        List<BigDecimal> issuedFaceValues = vouchers.findByMerchantIdAndIssuedAtBetween(m.getId(), from, to)
                .stream().map(Voucher::getValue).toList();
        List<BigDecimal> redeemedFaceValues = vouchers.findByMerchantIdAndRedeemedAtBetween(m.getId(), from, to)
                .stream().map(Voucher::getValue).toList();

        // V29: fee schedules now also live on loyalty rules (global rule = the
        // tenant standard, merchant rule = override) — EffectiveFees resolves
        // the precedence once per merchant; MerchantFeeCalculator still does
        // the row arithmetic.
        Billing billing = Billing.of(m,
                rules.findApplicable(m.getTenantId(), m.getId(), TransactionType.PURCHASE),
                issuedFaceValues, redeemedFaceValues);

        // No money owed -> no invoice.
        if (!billing.billable()) {
            return Optional.empty();
        }
        return Optional.of(persist(m, periodStart, periodEnd, billing));
    }

    /**
     * What one merchant owes for one period: the voucher counts and the fee
     * total, priced per voucher through {@link EffectiveFees}. Both invoice
     * paths build it — {@link #generate} from per-merchant reads, the nightly
     * run from its batched pre-load — so they cannot price differently.
     */
    record Billing(long vouchersIssued, long vouchersRedeemed, BigDecimal total) {

        static Billing of(Merchant m, List<LoyaltyRule> applicableRules,
                          List<BigDecimal> issuedFaceValues, List<BigDecimal> redeemedFaceValues) {
            EffectiveFees fees = EffectiveFees.resolve(m, applicableRules, Instant.now());
            BigDecimal feeVoucherIssued = issuedFaceValues.stream()
                    .map(fees::feeForIssuedFaceValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal feeVoucherRedeemed = redeemedFaceValues.stream()
                    .map(fees::feeForRedeemedFaceValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Billing(issuedFaceValues.size(), redeemedFaceValues.size(),
                    feeVoucherIssued.add(feeVoucherRedeemed));
        }

        /** compareTo-style (signum) rather than equals, so a NUMERIC(19,4) zero
         *  with any scale reads as zero. */
        boolean billable() {
            return total.signum() > 0;
        }
    }

    /**
     * Writes one merchant's invoice for a billable period, inside the caller's
     * transaction: the points figures, the row, the IN-9 stamp and the event.
     *
     * <p>The points sums run HERE, next to the stamp, in the same transaction —
     * not in the nightly run's pre-load. The stamp must cover exactly the rows
     * the sums counted, and a POSTED row can still change status (a reversal)
     * after a pre-load has read it; summing just before stamping keeps that
     * window as narrow as it always was.
     */
    private Invoice persist(Merchant m, LocalDate periodStart, LocalDate periodEnd, Billing billing) {
        Instant from = periodStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = periodEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        BigDecimal pointsIssued = transactions.sumPointsIssued(m.getId(), from, to);
        BigDecimal pointsRedeemed = transactions.sumPointsRedeemed(m.getId(), from, to);

        Invoice inv = new Invoice();
        inv.setTenantId(m.getTenantId());
        inv.setMerchantId(m.getId());
        inv.setPeriodStart(periodStart);
        inv.setPeriodEnd(periodEnd);
        inv.setPointsIssued(pointsIssued);
        inv.setPointsRedeemed(pointsRedeemed);
        inv.setVouchersIssued(billing.vouchersIssued());
        inv.setVouchersRedeemed(billing.vouchersRedeemed());
        inv.setTotalAmount(billing.total());
        inv.setCurrency(m.getCurrency());
        inv.setInvoiceNumber(nextInvoiceNumber());
        Invoice saved = invoices.save(inv);

        // IN-9: stamp the rows this invoice just billed, so a points report can
        // name the invoice a transaction was billed on instead of reconstructing
        // it from date ranges. Reuses the SAME from/to bounds the sums above
        // used, so the stamped set is exactly the summed set.
        //
        // Runs only on the non-zero-total path, which is deliberate: a period
        // with no billable voucher activity produces no invoice at all, so its
        // transactions keep invoice_id = NULL and the report correctly says
        // "not invoiced" rather than pointing at someone else's bill.
        int stamped = transactions.stampInvoice(saved.getId(), m.getId(), from, to);
        log.debug("Invoice {} stamped {} transaction(s) for merchant {} over [{}, {})",
                saved.getInvoiceNumber(), stamped, m.getId(), periodStart, periodEnd);

        // Email the merchant its invoice AFTER the tx commits (best-effort, async
        // — see InvoiceEmailNotifier). A value snapshot rides the event so the
        // post-commit listener needs no entity reload. In the nightly run each
        // merchant's transaction commits on its own, so each fires its own event.
        events.publishEvent(new InvoiceGeneratedEvent(
                m.getId(), m.getName(), m.getOrganizationId(), saved.getInvoiceNumber(),
                saved.getPeriodStart(), saved.getPeriodEnd(),
                saved.getVouchersIssued(), saved.getVouchersRedeemed(),
                saved.getTotalAmount(), saved.getCurrency()));
        return saved;
    }

    private String nextInvoiceNumber() {
        return props.invoice().prefix() + "-"
                + Instant.now().toEpochMilli() + "-"
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    public Invoice markPaid(UUID tenantId, UUID invoiceId) {
        Invoice inv = invoices.findById(invoiceId)
                .orElseThrow(() -> LoyaltyException.notFound("invoice"));
        if (!inv.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        // Object-level authz: a MERCHANT_ADMIN may only settle invoices of a
        // merchant they administer (SUPER_ADMIN operators bypass). Without this,
        // any merchant admin could mark a sibling merchant's invoice paid by id.
        merchantAuthz.requireCallerAdministersMerchant(tenantId, inv.getMerchantId());
        if (inv.getStatus() == Invoice.Status.PAID) {
            return inv;
        }
        inv.setStatus(Invoice.Status.PAID);
        inv.setPaidAt(Instant.now());
        return inv;
    }

    @Transactional(readOnly = true)
    public List<Dtos.InvoiceResponse> listForMerchant(UUID tenantId, UUID merchantId) {
        Merchant m = merchants.findById(merchantId)
                .orElseThrow(() -> LoyaltyException.notFound("merchant"));
        if (!m.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        return invoices.findByMerchantIdOrderByPeriodEndDesc(merchantId).stream()
                .map(InvoicingService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.InvoiceResponse> listForMerchant(UUID tenantId, UUID merchantId, Pageable pageable) {
        Merchant m = merchants.findById(merchantId)
                .orElseThrow(() -> LoyaltyException.notFound("merchant"));
        if (!m.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        return invoices.findByMerchantIdOrderByPeriodEndDesc(merchantId, pageable)
                .map(InvoicingService::toResponse);
    }

    public LocalDate previousPeriodStart(LocalDate today, Merchant.BillingCycle cycle) {
        return switch (cycle) {
            // DAILY bills the single completed day (yesterday). The invoice job
            // already runs daily, so each run closes off the prior day.
            case DAILY -> today.minusDays(1);
            case WEEKLY -> today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            case MONTHLY -> today.withDayOfMonth(1).minusMonths(1);
        };
    }

    public LocalDate previousPeriodEnd(LocalDate today, Merchant.BillingCycle cycle) {
        return switch (cycle) {
            case DAILY -> today.minusDays(1);
            case WEEKLY -> today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
            case MONTHLY -> today.withDayOfMonth(1).minusDays(1);
        };
    }

    /**
     * Run the periodic job: generate one PENDING invoice per active merchant for
     * the previous period.
     *
     * <p><b>Each merchant's invoice is its own transaction.</b> The run used to be
     * ONE transaction for every merchant, so a single merchant whose invoice
     * could not be written rolled back every invoice of the night, and the next
     * night's run started over from nothing. Now a merchant that fails is rolled
     * back alone, logged, and counted on {@value #RUN_FAILED_METRIC}; the others
     * commit, each firing its own {@code InvoiceGeneratedEvent} on its own
     * commit. A failed merchant is retried by the next run: nothing was
     * written for it, so the "already invoiced" check still lets it through.
     *
     * <p><b>The reads are batched, not per merchant.</b> Everything needed to
     * decide whether a merchant owes anything — which merchants are already
     * invoiced for their period, every applicable rule, every voucher issued or
     * redeemed in the period — is read up front with a fixed number of queries
     * (at most three billing periods, three queries each, plus the merchants
     * and the rules). A merchant with nothing to bill then costs no query at
     * all; one that is billed costs its own short write transaction (a re-check
     * that it is still un-invoiced, the two points sums, the insert, the stamp).
     * Before, every active merchant cost seven queries inside the one
     * transaction, billed or not.
     *
     * <p>Runs with NO surrounding transaction ({@code NOT_SUPPORTED}): the
     * per-merchant transactions must be top-level so each commits by itself.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int runPeriodicForAllMerchants(LocalDate today) {
        RunPlan plan = preloadTx.execute(status -> planRun(today));
        int created = 0;
        int failed = 0;
        for (PlannedInvoice p : plan == null ? List.<PlannedInvoice>of() : plan.billable()) {
            try {
                Invoice saved = perMerchantTx.execute(status -> writePlanned(p));
                if (saved != null) {
                    created++;
                }
            } catch (RuntimeException e) {
                // Rolled back alone (the template rolls back on a runtime
                // exception before rethrowing it); the rest of the run goes on.
                failed++;
                runFailures.increment();
                log.error("Invoice run: could not invoice merchant {} for [{}, {}] — rolled back, "
                                + "will be retried by the next run",
                        p.merchant().getId(), p.periodStart(), p.periodEnd(), e);
            }
        }
        if (failed > 0) {
            log.warn("Invoice run finished with {} invoice(s) created and {} merchant(s) failed",
                    created, failed);
        }
        return created;
    }

    /** One merchant's billable period, priced from the pre-load. */
    private record PlannedInvoice(Merchant merchant, LocalDate periodStart, LocalDate periodEnd,
                                  Billing billing) {}

    private record RunPlan(List<PlannedInvoice> billable) {}

    /** The merchants sharing one billing period this run (one per billing cycle in use). */
    private record PeriodGroup(LocalDate start, LocalDate end, List<Merchant> merchants) {}

    /**
     * The batched pre-load: every active merchant, grouped by the period its
     * billing cycle closes today; per period, the merchants already invoiced
     * for it and the face values of every voucher issued or redeemed in it; and
     * every rule of the merchants' tenants. Then prices each merchant in memory
     * through the same {@link Billing#of} as {@link #generate}, keeping only
     * the merchants that owe something.
     */
    private RunPlan planRun(LocalDate today) {
        Map<String, PeriodGroup> groups = new LinkedHashMap<>();
        Set<UUID> tenantIds = new HashSet<>();
        for (Merchant m : merchants.findAll()) {
            if (m.getStatus() != Merchant.Status.ACTIVE) continue;
            LocalDate start = previousPeriodStart(today, m.getBillingCycle());
            LocalDate end = previousPeriodEnd(today, m.getBillingCycle());
            groups.computeIfAbsent(start + "/" + end, k -> new PeriodGroup(start, end, new ArrayList<>()))
                    .merchants().add(m);
            tenantIds.add(m.getTenantId());
        }
        if (groups.isEmpty()) {
            return new RunPlan(List.of());
        }

        Map<UUID, List<LoyaltyRule>> rulesByTenant = new HashMap<>();
        for (LoyaltyRule r : rules.findByTenantIdIn(tenantIds)) {
            rulesByTenant.computeIfAbsent(r.getTenantId(), k -> new ArrayList<>()).add(r);
        }

        List<PlannedInvoice> billable = new ArrayList<>();
        for (PeriodGroup g : groups.values()) {
            List<UUID> ids = g.merchants().stream().map(Merchant::getId).toList();
            // Same bounds generate() uses: [start 00:00Z, end+1 00:00Z], BETWEEN.
            Instant from = g.start().atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant to = g.end().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

            Set<UUID> alreadyInvoiced = new HashSet<>(invoices.findMerchantIdsInvoicedFor(ids, g.start(), g.end()));
            Map<UUID, List<BigDecimal>> issued = faceValuesByMerchant(vouchers.issuedFaceValuesBetween(ids, from, to));
            Map<UUID, List<BigDecimal>> redeemed =
                    faceValuesByMerchant(vouchers.redeemedFaceValuesBetween(ids, from, to));

            for (Merchant m : g.merchants()) {
                if (alreadyInvoiced.contains(m.getId())) continue;
                try {
                    Billing billing = Billing.of(m,
                            EffectiveFees.applicable(rulesByTenant.get(m.getTenantId()), m.getId(),
                                    TransactionType.PURCHASE),
                            issued.getOrDefault(m.getId(), List.of()),
                            redeemed.getOrDefault(m.getId(), List.of()));
                    if (billing.billable()) {
                        billable.add(new PlannedInvoice(m, g.start(), g.end(), billing));
                    }
                } catch (RuntimeException e) {
                    // A merchant whose fees cannot be priced fails alone, like a
                    // merchant whose invoice cannot be written.
                    runFailures.increment();
                    log.error("Invoice run: could not price merchant {} for [{}, {}] — skipped, "
                            + "will be retried by the next run", m.getId(), g.start(), g.end(), e);
                }
            }
        }
        return new RunPlan(billable);
    }

    private static Map<UUID, List<BigDecimal>> faceValuesByMerchant(List<Object[]> rows) {
        Map<UUID, List<BigDecimal>> out = new HashMap<>();
        for (Object[] r : rows) {
            out.computeIfAbsent((UUID) r[0], k -> new ArrayList<>()).add((BigDecimal) r[2]);
        }
        return out;
    }

    /**
     * One merchant's write, inside its own transaction. Re-checks that the
     * period is still un-invoiced — the idempotency guard the run has always
     * had, now asked again where it counts, since a manual
     * {@code POST /loyalty/invoices/generate} may have landed after the
     * pre-load — and returns {@code null} (nothing written) if it is not.
     */
    private Invoice writePlanned(PlannedInvoice p) {
        Merchant m = p.merchant();
        if (invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(m.getId(), p.periodStart(), p.periodEnd())
                .isPresent()) {
            return null;
        }
        return persist(m, p.periodStart(), p.periodEnd(), p.billing());
    }

    public static Dtos.InvoiceResponse toResponse(Invoice inv) {
        return new Dtos.InvoiceResponse(inv.getId(), inv.getInvoiceNumber(), inv.getMerchantId(),
                inv.getPeriodStart(), inv.getPeriodEnd(), inv.getPointsIssued(),
                inv.getPointsRedeemed(), inv.getVouchersIssued(), inv.getVouchersRedeemed(),
                inv.getTotalAmount(), inv.getCurrency(), inv.getStatus().name(), inv.getPaidAt());
    }
}
