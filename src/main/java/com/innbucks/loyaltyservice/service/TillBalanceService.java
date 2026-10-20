package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * What a customer can pay with points at a till, before the checkout.
 *
 * <p><b>Read-only, on purpose.</b> It never creates a projection or a wallet and
 * never asks the InnBucks directory to register a phone ({@link
 * UserService#spendabilityOf} does both, so it is not used here): a read must
 * not change state. The checkout's spend gate still decides at the moment of
 * spending, exactly as before.
 *
 * <p><b>"Use all" never overshoots.</b> {@code pointsValue} is the largest
 * amount, in the merchant's currency and to the cent, whose points the burn
 * computes ({@code RedemptionService}: local → USD → whole points, HALF_UP) at
 * no more than the customer holds. It is checked through the same conversion
 * the burn uses, so a till that sends it back cannot be refused
 * {@code INSUFFICIENT_FUNDS} for a rounding cent.
 */
@Service
@Slf4j
public class TillBalanceService {

    /** Cents tried below the first estimate before giving up; an FX round trip is off by one or two. */
    private static final int MAX_CENT_STEPS = 100;
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private final UserService users;
    private final MerchantService merchants;
    private final WalletRepository wallets;
    private final RedemptionRateService rates;
    private final ExchangeRateService fx;
    private final SupportedCurrencies supportedCurrencies;
    private final LoyaltyMetrics metrics;

    public TillBalanceService(UserService users, MerchantService merchants, WalletRepository wallets,
                              RedemptionRateService rates, ExchangeRateService fx,
                              SupportedCurrencies supportedCurrencies, LoyaltyMetrics metrics) {
        this.users = users;
        this.merchants = merchants;
        this.wallets = wallets;
        this.rates = rates;
        this.fx = fx;
        this.supportedCurrencies = supportedCurrencies;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public Dtos.TillBalanceResponse lookup(UUID tenantId, UUID merchantId, String rawPhone) {
        String phone = users.normalizePhone(rawPhone);
        Merchant merchant = merchants.requireMerchant(tenantId, merchantId);
        String currency = supportedCurrencies.requireSupported(merchant.getCurrency());

        // The MAIN wallet only: it is the one a burn debits, so a figure that
        // included other pockets could promise points the checkout cannot spend.
        BigDecimal points = wallets.findFirstByPhoneNumberAndType(phone, Wallet.Type.MAIN)
                .map(Wallet::getBalance)
                .orElse(BigDecimal.ZERO)
                .max(BigDecimal.ZERO)
                .setScale(0, RoundingMode.DOWN);
        BigDecimal pointsPerUnit = rates.currentRate(SupportedCurrencies.BASE).getPointsPerUnit();
        BigDecimal value = spendableValue(tenantId, points, pointsPerUnit, currency);

        Optional<LoyaltyUser> account = users.findByPhone(tenantId, phone);
        String status = account.map(this::statusOf).orElse("NONE");
        metrics.incTillBalanceLookup(account.isPresent()
                ? LoyaltyMetrics.TILL_LOOKUP_FOUND : LoyaltyMetrics.TILL_LOOKUP_NONE);
        log.info("Till balance lookup tenant={} merchant={} phone={} status={}",
                tenantId, merchantId, MsisdnMasking.mask(phone), status);

        return new Dtos.TillBalanceResponse(MsisdnMasking.mask(phone), points, value, currency,
                pointsPerUnit, status, "ACTIVE".equals(status));
    }

    /**
     * A PENDING row whose phone is in fact registered is a stale cache (V40):
     * the burn heals it and spends, so it reads as ACTIVE here. Asked through
     * {@link UserService#isRegistrationPending}, which has no side effects.
     */
    private String statusOf(LoyaltyUser u) {
        return switch (u.getStatus()) {
            case ACTIVE -> "ACTIVE";
            case BLOCKED -> "BLOCKED";
            case INACTIVE -> "INACTIVE";
            case PENDING -> users.isRegistrationPending(u) ? "PENDING" : "ACTIVE";
        };
    }

    /** The largest cent amount whose burn needs no more than {@code points}. */
    private BigDecimal spendableValue(UUID tenantId, BigDecimal points, BigDecimal pointsPerUnit, String currency) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        if (points.signum() <= 0) {
            return zero;
        }
        BigDecimal base = points.divide(pointsPerUnit, 4, RoundingMode.DOWN);
        BigDecimal local = fx.fromBase(tenantId, base, currency).setScale(2, RoundingMode.DOWN);
        for (int i = 0; i < MAX_CENT_STEPS && local.signum() > 0; i++) {
            BigDecimal needed = rates.pointsFor(fx.toBase(tenantId, local, currency), SupportedCurrencies.BASE);
            if (needed.compareTo(points) <= 0) {
                return local;
            }
            local = local.subtract(CENT);
        }
        return zero;
    }
}
