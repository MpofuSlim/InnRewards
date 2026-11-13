package com.innbucks.loyaltyservice.service.partner;

import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.Amount;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.CurrencyStatement;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.MerchantAmount;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.MerchantCurrencyLine;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.MerchantLine;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.MerchantStatement;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.MethodAmount;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.OwedUse;
import com.innbucks.loyaltyservice.dto.PartnerMoneyDtos.PlatformStatement;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Balances;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Keyed;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.LinkedMerchant;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.MerchantMoney;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.MethodMoney;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.Money;
import com.innbucks.loyaltyservice.service.partner.PartnerLedger.OwedItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Who is owed what for partner vouchers (V70). A merchant's statement says what
 * was used at it and by whose vouchers, what InnBucks paid it from holding, what
 * is still owed to it (with each use), what it sold into holding, and how much of
 * its vouchers' value is unspent. The platform view lists every partner merchant
 * with the same headline figures.
 *
 * <p>Periods are UTC days, {@code from} and {@code to} inclusive (the reports'
 * convention); left out, the period is this month to date. "Owed", "unspent" and
 * "expired" are as they stand now, whatever the period. Everything is read in
 * one read-only, repeatable-read transaction so the figures agree with each other.
 */
@Service
public class PartnerVoucherStatements {

    /** Most owed uses listed on one statement. The totals always count them all. */
    public static final int OWED_USES_LIMIT = 200;

    private final PartnerLedger ledger;
    private final MerchantRepository merchants;
    private final MerchantAuthz merchantAuthz;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PartnerVoucherStatements(PartnerLedger ledger, MerchantRepository merchants, MerchantAuthz merchantAuthz) {
        this(ledger, merchants, merchantAuthz, Clock.systemUTC());
    }

    PartnerVoucherStatements(PartnerLedger ledger, MerchantRepository merchants, MerchantAuthz merchantAuthz,
                             Clock clock) {
        this.ledger = ledger;
        this.merchants = merchants;
        this.merchantAuthz = merchantAuthz;
        this.clock = clock;
    }

    /** One merchant's statement. Its own admin, SUPER_ADMIN, or a read-only platform reader. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MerchantStatement merchantStatement(UUID merchantId, LocalDate from, LocalDate to) {
        Merchant m = merchants.findById(merchantId).orElseThrow(() -> LoyaltyException.notFound("merchant"));
        merchantAuthz.requireCallerMayReadMerchant(m.getTenantId(), m.getId());
        Period p = period(from, to);
        Instant now = clock.instant();

        List<MerchantMoney> redeemedHere = ledger.redeemedAtByIssuer(m.getId(), p.start(), p.end());
        Map<String, Money> settled = ledger.settledTo(m.getId(), p.start(), p.end());
        Map<String, Money> paidAtRedeem = ledger.paidAtRedeemTo(m.getId(), p.start(), p.end());
        Map<String, Money> owed = ledger.owedTo(m.getId());
        List<MethodMoney> sold = ledger.soldBy(m.getId(), p.start(), p.end());
        List<MerchantMoney> issuedRedeemed = ledger.issuedRedeemedAt(m.getId(), p.start(), p.end());
        Map<String, Balances> balances = ledger.issuerBalances(m.getId(), now);
        List<OwedItem> owedItems = ledger.owedItems(m.getId(), OWED_USES_LIMIT + 1);

        Set<String> currencies = new TreeSet<>();
        redeemedHere.forEach(x -> currencies.add(x.currency()));
        currencies.addAll(settled.keySet());
        currencies.addAll(paidAtRedeem.keySet());
        currencies.addAll(owed.keySet());
        sold.forEach(x -> currencies.add(x.currency()));
        issuedRedeemed.forEach(x -> currencies.add(x.currency()));
        currencies.addAll(balances.keySet());

        List<CurrencyStatement> lines = new ArrayList<>();
        for (String c : currencies) {
            List<MerchantAmount> byIssuer = redeemedHere.stream().filter(x -> c.equals(x.currency()))
                    .map(PartnerVoucherStatements::merchantAmount).toList();
            List<MethodAmount> byMethod = sold.stream().filter(x -> c.equals(x.currency()))
                    .map(x -> new MethodAmount(x.method(), scaled(x.money().amount()), x.money().count()))
                    .toList();
            List<MerchantAmount> redeemedAt = issuedRedeemed.stream().filter(x -> c.equals(x.currency()))
                    .map(PartnerVoucherStatements::merchantAmount).toList();
            Balances b = balances.getOrDefault(c, Balances.ZERO);
            lines.add(new CurrencyStatement(c,
                    sum(byIssuer.stream().map(x -> new Amount(x.amount(), x.count())).toList()),
                    byIssuer,
                    amount(settled.get(c)),
                    amount(paidAtRedeem.get(c)),
                    amount(owed.get(c)),
                    sum(byMethod.stream().map(x -> new Amount(x.amount(), x.count())).toList()),
                    byMethod,
                    redeemedAt,
                    amount(b.outstanding()),
                    amount(b.expired())));
        }

        boolean truncated = owedItems.size() > OWED_USES_LIMIT;
        List<OwedUse> owedUses = owedItems.stream().limit(OWED_USES_LIMIT)
                .map(i -> new OwedUse(i.redemptionId(), i.redeemedAt(), i.reference(), i.issuerMerchantId(),
                        i.issuerName(), scaled(i.amount()), i.currency(), i.settlementStatus()))
                .toList();
        return new MerchantStatement(m.getId(), m.getName(), p.from(), p.to(), now, List.copyOf(lines),
                owedUses, truncated);
    }

    /** Every partner merchant's headline figures. SUPER_ADMIN or a read-only platform reader. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlatformStatement platformStatement(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        List<LinkedMerchant> linked = ledger.linkedMerchants();
        Map<UUID, Map<String, Money>> sold = byMerchant(ledger.soldPerMerchant(p.start(), p.end()));
        Map<UUID, Map<String, Money>> redeemed = byMerchant(ledger.redeemedPerMerchant(p.start(), p.end()));
        Map<UUID, Map<String, Money>> owed = byMerchant(ledger.owedPerMerchant());
        Map<UUID, Map<String, Money>> unspent = byMerchant(ledger.unspentPerIssuer());

        List<MerchantLine> rows = new ArrayList<>();
        for (LinkedMerchant lm : linked) {
            Set<String> currencies = new TreeSet<>();
            for (Map<UUID, Map<String, Money>> figure : List.of(sold, redeemed, owed, unspent)) {
                currencies.addAll(figure.getOrDefault(lm.merchantId(), Map.of()).keySet());
            }
            List<MerchantCurrencyLine> lines = new ArrayList<>();
            for (String c : currencies) {
                lines.add(new MerchantCurrencyLine(c,
                        amount(get(sold, lm.merchantId(), c)),
                        amount(get(redeemed, lm.merchantId(), c)),
                        amount(get(owed, lm.merchantId(), c)),
                        amount(get(unspent, lm.merchantId(), c))));
            }
            List<String> codes = lm.codes() == null ? List.of() : Arrays.asList(lm.codes().split(","));
            rows.add(new MerchantLine(lm.merchantId(), lm.name(), lm.tenantId(), List.copyOf(codes),
                    List.copyOf(lines)));
        }
        return new PlatformStatement(p.from(), p.to(), clock.instant(), List.copyOf(rows));
    }

    // ---------------------------------------------------------------- helpers

    /** A resolved period: the days asked for, and the half-open instants they cover. */
    record Period(LocalDate from, LocalDate to, Instant start, Instant end) {}

    Period period(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate f = from != null ? from : (to != null ? to.withDayOfMonth(1) : today.withDayOfMonth(1));
        LocalDate t = to != null ? to : today;
        if (f.isAfter(t)) {
            throw LoyaltyException.badRequest("RANGE_INVERTED", "from must not be after to");
        }
        return new Period(f, t, f.atStartOfDay().toInstant(ZoneOffset.UTC),
                t.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private static Map<UUID, Map<String, Money>> byMerchant(List<Keyed> rows) {
        Map<UUID, Map<String, Money>> out = new LinkedHashMap<>();
        for (Keyed k : rows) {
            out.computeIfAbsent(k.merchantId(), x -> new LinkedHashMap<>()).put(k.currency(), k.money());
        }
        return out;
    }

    private static Money get(Map<UUID, Map<String, Money>> m, UUID merchantId, String currency) {
        return m.getOrDefault(merchantId, Map.of()).get(currency);
    }

    private static MerchantAmount merchantAmount(MerchantMoney x) {
        return new MerchantAmount(x.merchantId(), x.merchantName(), scaled(x.money().amount()), x.money().count());
    }

    private static Amount amount(Money m) {
        return m == null ? new Amount(scaled(BigDecimal.ZERO), 0) : new Amount(scaled(m.amount()), m.count());
    }

    private static Amount sum(List<Amount> parts) {
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        for (Amount a : parts) {
            total = total.add(a.amount());
            count += a.count();
        }
        return new Amount(scaled(total), count);
    }

    static BigDecimal scaled(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
