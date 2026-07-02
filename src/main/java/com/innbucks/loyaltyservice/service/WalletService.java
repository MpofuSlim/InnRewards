package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.PointLot;
import com.innbucks.loyaltyservice.entity.PointsLedger;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.PointLotRepository;
import com.innbucks.loyaltyservice.repository.PointsLedgerRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.util.MsisdnValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Wallets are GLOBAL per customer (keyed by the platform-stable phone number),
 * not per tenant: a customer has one MAIN wallet across the whole super-app, so
 * points earned at any tenant are spendable at any tenant.
 *
 * <p>Points carry a per-lot expiry. Every credit ({@link #apply} with a positive
 * delta) opens a {@link PointLot} that expires {@code expiryDays} after it is
 * earned; every debit burns lots FIFO (soonest-to-expire first). The wallet's
 * cached {@code balance} equals the sum of {@code remaining} across the
 * customer's LIVE lots — an invariant maintained here (lazy release of expired
 * lots on every touch) and by the daily {@code PointExpirySweeper} (backstop for
 * idle wallets). Expired remainders are released to the ledger as "breakage".
 */
@Service
@Transactional
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;
    private final PointsLedgerRepository ledger;
    private final PointLotRepository lots;
    private final LoyaltyMetrics metrics;
    private final int expiryDays;

    /** Cell country pin — region hint for best-effort E.164 normalisation of a
     *  wallet's phone key. Defaults to ZW for plain-`new` unit tests; @Value
     *  overrides from INNBUCKS_COUNTRY at runtime. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    public WalletService(WalletRepository wallets, PointsLedgerRepository ledger,
                         PointLotRepository lots, LoyaltyMetrics metrics,
                         @Value("${loyalty.points.expiry-days:30}") int expiryDays) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.lots = lots;
        this.metrics = metrics;
        this.expiryDays = expiryDays;
    }

    @Transactional(readOnly = true)
    public List<Dtos.WalletResponse> listForPhone(String phoneNumber) {
        return wallets.findByPhoneNumber(normalizePhone(phoneNumber)).stream().map(WalletService::toResponse).toList();
    }

    public Dtos.WalletResponse createSubWallet(String phoneNumber, Dtos.SubWalletRequest req) {
        phoneNumber = normalizePhone(phoneNumber);
        Wallet w = new Wallet();
        w.setPhoneNumber(phoneNumber);
        w.setLabel(req.label());
        w.setPocket(req.pocket());
        if (req.type() != null) {
            try { w.setType(Wallet.Type.valueOf(req.type())); }
            catch (IllegalArgumentException e) { throw LoyaltyException.badRequest("BAD_TYPE", "invalid wallet type"); }
        } else {
            w.setType(Wallet.Type.SUB);
        }
        if (w.getType() == Wallet.Type.MAIN) {
            throw LoyaltyException.badRequest("BAD_TYPE", "MAIN wallet is created automatically");
        }
        w.setLockedUntil(req.lockedUntil());
        wallets.save(w);
        return toResponse(w);
    }

    /**
     * The customer's single global MAIN wallet, created on first use. Race-safe:
     * a concurrent first-use that loses the unique-index race re-reads the
     * winner's row instead of creating a duplicate.
     */
    public Wallet mainWallet(String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        return wallets.findFirstByPhoneNumberAndType(phone, Wallet.Type.MAIN)
                .orElseGet(() -> createMainWallet(phone));
    }

    /** Best-effort E.164 of a wallet's phone key against this cell's country.
     *  Idempotent, and never throws — an unparseable value passes through
     *  trimmed so an internal caller that already holds a stored phone is
     *  unaffected. Keeps a wallet keyed by the same string user/loyalty store. */
    private String normalizePhone(String raw) {
        return MsisdnValidator.normalizeToE164(raw, deploymentCountry)
                .orElse(raw == null ? null : raw.trim());
    }

    private Wallet createMainWallet(String phoneNumber) {
        Wallet w = new Wallet();
        w.setPhoneNumber(phoneNumber);
        w.setLabel("Main");
        w.setType(Wallet.Type.MAIN);
        try {
            return wallets.saveAndFlush(w);
        } catch (DataIntegrityViolationException race) {
            // Another thread created the customer's MAIN wallet first (uk_wallet_main).
            return wallets.findFirstByPhoneNumberAndType(phoneNumber, Wallet.Type.MAIN)
                    .orElseThrow(() -> race);
        }
    }

    /**
     * Apply a delta atomically to a wallet using a row-level pessimistic lock and
     * record a ledger entry. Expired lots are released first (so a debit can't
     * spend expired points and the balance stays accurate), then a credit opens a
     * new lot and a debit burns lots FIFO. {@code ledgerTenantId} is the tenant
     * where the activity originated; the global wallet itself is tenant-less.
     */
    public BigDecimal apply(UUID walletId, BigDecimal delta, UUID transactionId, String reason, UUID ledgerTenantId) {
        Wallet w = wallets.lockById(walletId)
                .orElseThrow(() -> LoyaltyException.notFound("wallet"));
        Instant now = Instant.now();

        // Release lots that expired since this wallet was last touched, so the
        // balance reflects only live points and a debit can't spend expired ones.
        releaseExpiredLots(w, now);

        if (delta.signum() < 0 && w.getLockedUntil() != null
                && LocalDate.now(ZoneOffset.UTC).isBefore(w.getLockedUntil())) {
            throw LoyaltyException.badRequest("WALLET_LOCKED", "wallet is locked until " + w.getLockedUntil());
        }
        BigDecimal newBalance = w.getBalance().add(delta);
        if (newBalance.signum() < 0) {
            throw LoyaltyException.badRequest("INSUFFICIENT_FUNDS", "You don't have enough loyalty points for this.");
        }
        w.setBalance(newBalance);
        ledger.save(ledgerEntry(walletId, ledgerTenantId, transactionId, delta, newBalance, reason));

        if (delta.signum() > 0) {
            PointLot lot = new PointLot();
            lot.setWalletId(walletId);
            lot.setTenantId(ledgerTenantId);
            lot.setSourceTransactionId(transactionId);
            lot.setOriginalAmount(delta);
            lot.setRemainingAmount(delta);
            lot.setEarnedAt(now);
            lot.setExpiresAt(now.plus(expiryDays, ChronoUnit.DAYS));
            lots.save(lot);
        } else if (delta.signum() < 0) {
            consumeFifo(walletId, delta.negate(), now);
        }
        return newBalance;
    }

    /**
     * Release a wallet's expired-but-unreleased lots (breakage). Locks the wallet
     * then defers to {@link #releaseExpiredLots}. Called by the daily sweeper for
     * idle wallets that {@link #apply} hasn't touched.
     */
    public void expireDueLots(UUID walletId) {
        Wallet w = wallets.lockById(walletId).orElse(null);
        if (w == null) return;
        releaseExpiredLots(w, Instant.now());
    }

    /**
     * Reconciliation repair: reset a wallet's cached balance to the sum of its
     * ledger deltas — the append-only financial source of truth. Driven by the
     * daily {@link com.innbucks.loyaltyservice.scheduler.BalanceReconciliationJob}
     * when (and only when) auto-fix is enabled; otherwise drift is detect-only.
     *
     * <p>Deliberately writes NO ledger entry: the ledger already records every
     * real movement, so the fix is to make the <em>cache</em> agree with the
     * ledger, not to append a new "truth" matching a broken cache. Runs under
     * the wallet's row lock in its own transaction. Returns the rebuilt balance.
     */
    public BigDecimal rebuildBalanceFromLedger(UUID walletId) {
        Wallet w = wallets.lockById(walletId)
                .orElseThrow(() -> LoyaltyException.notFound("wallet"));
        BigDecimal ledgerSum = ledger.sumDeltaByWalletId(walletId);
        BigDecimal previous = w.getBalance();
        if (previous.compareTo(ledgerSum) == 0) return ledgerSum; // raced back to consistency
        w.setBalance(ledgerSum);
        log.warn("Reconciliation rebuilt wallet {} balance {} -> {} (sum of ledger deltas)",
                walletId, previous, ledgerSum);
        return ledgerSum;
    }

    /** Caller must hold the wallet's row lock. */
    private void releaseExpiredLots(Wallet w, Instant now) {
        List<PointLot> due = lots.findDueForExpiry(w.getId(), now);
        for (PointLot lot : due) {
            BigDecimal amt = lot.getRemainingAmount();
            if (amt == null || amt.signum() <= 0) continue;
            BigDecimal newBalance = w.getBalance().subtract(amt);
            if (newBalance.signum() < 0) newBalance = BigDecimal.ZERO; // defensive; invariant says >= 0
            w.setBalance(newBalance);
            ledger.save(ledgerEntry(w.getId(), lot.getTenantId(), null,
                    amt.negate(), newBalance, "expiry:lot=" + lot.getId()));
            lot.setRemainingAmount(BigDecimal.ZERO);
            lots.save(lot);
            metrics.addPointsExpired(amt);
        }
    }

    /** Caller must hold the wallet's row lock. Burns {@code amount} across live lots, soonest-to-expire first. */
    private void consumeFifo(UUID walletId, BigDecimal amount, Instant now) {
        BigDecimal remaining = amount;
        for (PointLot lot : lots.findLiveForConsumption(walletId, now)) {
            if (remaining.signum() <= 0) break;
            BigDecimal take = lot.getRemainingAmount().min(remaining);
            lot.setRemainingAmount(lot.getRemainingAmount().subtract(take));
            lots.save(lot);
            remaining = remaining.subtract(take);
        }
        // After releaseExpiredLots, balance == sum(live lots) and the debit was
        // checked against balance, so live lots cover it. A leftover signals
        // balance/lot drift; surface it (the reconciliation job will flag it)
        // but don't fail a money op whose balance check already passed.
        if (remaining.signum() > 0) {
            log.warn("FIFO under-coverage on wallet {} by {} points — balance/lot drift", walletId, remaining);
        }
    }

    private static PointsLedger ledgerEntry(UUID walletId, UUID tenantId, UUID transactionId,
                                            BigDecimal delta, BigDecimal balanceAfter, String reason) {
        PointsLedger entry = new PointsLedger();
        entry.setTenantId(tenantId);
        entry.setWalletId(walletId);
        entry.setTransactionId(transactionId);
        entry.setDelta(delta);
        entry.setBalanceAfter(balanceAfter);
        entry.setReason(reason);
        return entry;
    }

    @Transactional(readOnly = true)
    public BigDecimal totalBalance(String phoneNumber) {
        return wallets.findByPhoneNumber(normalizePhone(phoneNumber)).stream()
                .map(Wallet::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Dtos.WalletResponse toResponse(Wallet w) {
        return new Dtos.WalletResponse(w.getId(), w.getUserId(), w.getLabel(),
                w.getType().name(), w.getPocket(), w.getBalance(), w.getLockedUntil());
    }
}
