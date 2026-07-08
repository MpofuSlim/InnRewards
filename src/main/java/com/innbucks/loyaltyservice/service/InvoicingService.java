package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Invoice;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.InvoiceGeneratedEvent;
import com.innbucks.loyaltyservice.repository.InvoiceRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class InvoicingService {

    private final InvoiceRepository invoices;
    private final MerchantRepository merchants;
    private final LoyaltyTransactionRepository transactions;
    private final VoucherRepository vouchers;
    private final LoyaltyProperties props;
    private final com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz;
    private final ApplicationEventPublisher events;

    public InvoicingService(InvoiceRepository invoices, MerchantRepository merchants,
                            LoyaltyTransactionRepository transactions,
                            VoucherRepository vouchers,
                            LoyaltyProperties props,
                            com.innbucks.loyaltyservice.security.MerchantAuthz merchantAuthz,
                            ApplicationEventPublisher events) {
        this.invoices = invoices;
        this.merchants = merchants;
        this.transactions = transactions;
        this.vouchers = vouchers;
        this.props = props;
        this.merchantAuthz = merchantAuthz;
        this.events = events;
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

        BigDecimal pointsIssued = transactions.sumPointsIssued(m.getId(), from, to);
        BigDecimal pointsRedeemed = transactions.sumPointsRedeemed(m.getId(), from, to);

        // Pull individual vouchers (not just COUNT) so PERCENTAGE / FIXED_PLUS_
        // PERCENTAGE fees can multiply each row's face value by the merchant's
        // configured percentage. For FIXED, this still sums to count*flat —
        // MerchantFeeCalculator.compute returns the same value for every row.
        List<Voucher> issuedVouchers   = vouchers.findByMerchantIdAndIssuedAtBetween(m.getId(), from, to);
        List<Voucher> redeemedVouchers = vouchers.findByMerchantIdAndRedeemedAtBetween(m.getId(), from, to);
        long voucherIssued   = issuedVouchers.size();
        long voucherRedeemed = redeemedVouchers.size();

        BigDecimal feeVoucherIssued = issuedVouchers.stream()
                .map(v -> MerchantFeeCalculator.feeForIssued(m, v))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeVoucherRedeemed = redeemedVouchers.stream()
                .map(v -> MerchantFeeCalculator.feeForRedeemed(m, v))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = feeVoucherIssued.add(feeVoucherRedeemed);

        // No money owed -> no invoice. compareTo(ZERO) instead of equals so
        // a NUMERIC(19,4) zero with any scale matches.
        if (total.signum() <= 0) {
            return Optional.empty();
        }

        Invoice inv = new Invoice();
        inv.setTenantId(m.getTenantId());
        inv.setMerchantId(m.getId());
        inv.setPeriodStart(periodStart);
        inv.setPeriodEnd(periodEnd);
        inv.setPointsIssued(pointsIssued);
        inv.setPointsRedeemed(pointsRedeemed);
        inv.setVouchersIssued(voucherIssued);
        inv.setVouchersRedeemed(voucherRedeemed);
        inv.setTotalAmount(total);
        inv.setCurrency(m.getCurrency());
        inv.setInvoiceNumber(nextInvoiceNumber());
        Invoice saved = invoices.save(inv);
        // Email the merchant its invoice AFTER the tx commits (best-effort, async
        // — see InvoiceEmailNotifier). A value snapshot rides the event so the
        // post-commit listener needs no entity reload.
        events.publishEvent(new InvoiceGeneratedEvent(
                m.getId(), m.getName(), m.getAdminEmail(), saved.getInvoiceNumber(),
                saved.getPeriodStart(), saved.getPeriodEnd(),
                saved.getVouchersIssued(), saved.getVouchersRedeemed(),
                saved.getTotalAmount(), saved.getCurrency()));
        return Optional.of(saved);
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

    /** Run the periodic job: generate one PENDING invoice per active merchant for the previous period. */
    public int runPeriodicForAllMerchants(LocalDate today) {
        int created = 0;
        for (Merchant m : merchants.findAll()) {
            if (m.getStatus() != Merchant.Status.ACTIVE) continue;
            LocalDate start = previousPeriodStart(today, m.getBillingCycle());
            LocalDate end = previousPeriodEnd(today, m.getBillingCycle());
            if (invoices.findByMerchantIdAndPeriodStartAndPeriodEnd(m.getId(), start, end).isEmpty()) {
                if (generate(m, start, end).isPresent()) {
                    created++;
                }
            }
        }
        return created;
    }

    public static Dtos.InvoiceResponse toResponse(Invoice inv) {
        return new Dtos.InvoiceResponse(inv.getId(), inv.getInvoiceNumber(), inv.getMerchantId(),
                inv.getPeriodStart(), inv.getPeriodEnd(), inv.getPointsIssued(),
                inv.getPointsRedeemed(), inv.getVouchersIssued(), inv.getVouchersRedeemed(),
                inv.getTotalAmount(), inv.getCurrency(), inv.getStatus().name(), inv.getPaidAt());
    }
}
