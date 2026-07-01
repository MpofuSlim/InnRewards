package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bridges ticket bookings to the loyalty ledger: an event organizer (the stable
 * user_uuid booking carries as {@code tenant_user_uuid}) maps to one loyalty
 * {@link Merchant}, auto-provisioned on first contact under the seeded Ticketing
 * tenant. Earn and redeem are idempotent on the booking {@code reference}.
 * Service-to-service only (reached via X-Internal-Token), so no TenantContext.
 */
@Service
@Transactional
public class TicketingLoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(TicketingLoyaltyService.class);

    /** The platform Ticketing tenant, seeded with this fixed id by V23. */
    public static final UUID TICKETING_TENANT_ID =
            UUID.fromString("0a571c1c-7c75-4000-a000-000000000001");

    private final MerchantRepository merchants;
    private final LoyaltyRuleRepository rules;
    private final UserService users;
    private final TransactionService transactionService;
    private final RedemptionService redemptionService;
    private final WalletService walletService;
    private final LoyaltyTransactionRepository transactions;

    @Value("${innbucks.currency:USD}")
    private String cellCurrency;
    @Value("${loyalty.ticketing.default-earn-rate:1}")
    private BigDecimal defaultEarnRate;
    @Value("${loyalty.points.redeem-rate:100}")
    private BigDecimal redeemRate;

    public TicketingLoyaltyService(MerchantRepository merchants, LoyaltyRuleRepository rules,
                                   UserService users, TransactionService transactionService,
                                   RedemptionService redemptionService, WalletService walletService,
                                   LoyaltyTransactionRepository transactions) {
        this.merchants = merchants;
        this.rules = rules;
        this.users = users;
        this.transactionService = transactionService;
        this.redemptionService = redemptionService;
        this.walletService = walletService;
        this.transactions = transactions;
    }

    public record TicketingRule(UUID tenantId, UUID merchantId, BigDecimal earnRate,
                                BigDecimal redeemRate, String currency) {}

    public record EarnResult(UUID transactionId, UUID merchantId, BigDecimal pointsEarned,
                             BigDecimal balanceAfter, boolean replayed) {}

    public record RedeemResult(UUID transactionId, UUID merchantId, BigDecimal balanceAfter) {}

    @Transactional
    public TicketingRule rule(UUID organizerUuid) {
        requireOrganizer(organizerUuid);
        Merchant m = resolveMerchant(organizerUuid);
        BigDecimal earnRate = rules.findApplicable(TICKETING_TENANT_ID, m.getId(), TransactionType.PURCHASE)
                .stream().findFirst().map(LoyaltyRule::getPointsPerUnit).orElse(defaultEarnRate);
        return new TicketingRule(TICKETING_TENANT_ID, m.getId(), earnRate, redeemRate, m.getCurrency());
    }

    @Transactional
    public EarnResult earn(UUID organizerUuid, String phoneNumber, BigDecimal cashAmount, String reference) {
        requireOrganizer(organizerUuid);
        requirePhone(phoneNumber);
        if (cashAmount == null || cashAmount.signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_AMOUNT", "cashAmount must be greater than zero");
        }
        Merchant m = resolveMerchant(organizerUuid);
        String ref = earnRef(reference);
        // Idempotency pre-check: a prior earn for this booking replays here so we
        // never call post() on a replay — post()'s DUPLICATE_REFERENCE throw marks
        // the shared transaction rollback-only and fails the commit. This also
        // makes the replay return a success, so booking's earn-retry job drains to
        // succeeded instead of looping to giving_up.
        if (ref != null) {
            var prior = transactions.findFirstByMerchantIdAndReference(m.getId(), ref);
            if (prior.isPresent()) {
                LoyaltyTransaction t = prior.get();
                return new EarnResult(t.getId(), m.getId(),
                        t.getPointsDelta() == null ? BigDecimal.ZERO : t.getPointsDelta(),
                        walletService.mainWallet(phoneNumber).getBalance(), true);
            }
        }
        Dtos.TransactionRequest req = new Dtos.TransactionRequest(
                m.getId(), null, phoneNumber, TransactionType.PURCHASE,
                cashAmount, m.getCurrency(), ref);
        Dtos.TransactionResponse resp = transactionService.post(TICKETING_TENANT_ID, m.getId(), req);
        return new EarnResult(resp.id(), m.getId(),
                resp.pointsDelta() == null ? BigDecimal.ZERO : resp.pointsDelta(),
                resp.balanceAfter(), false);
    }

    @Transactional
    public RedeemResult redeem(UUID organizerUuid, String phoneNumber, BigDecimal points, String reference) {
        requireOrganizer(organizerUuid);
        requirePhone(phoneNumber);
        if (points == null || points.signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_AMOUNT", "points must be greater than zero");
        }
        Merchant m = resolveMerchant(organizerUuid);
        LoyaltyUser user = users.findOrCreatePending(TICKETING_TENANT_ID, phoneNumber, m.getId());
        Dtos.RedemptionRequest req = new Dtos.RedemptionRequest(
                m.getId(), user.getId(), points, "ticket-redeem", redeemRef(reference));
        RedemptionService.RedemptionResult r = redemptionService.redeemPoints(TICKETING_TENANT_ID, m.getId(), req);
        return new RedeemResult(r.transactionId(), m.getId(), r.balance());
    }

    // Earn and redeem for one booking share the merchant, and uq_txn_merchant_reference
    // spans both PURCHASE and REDEMPTION, so they must use distinct references or the
    // second leg of a split payment collides.
    private static String earnRef(String reference) {
        return reference == null ? null : reference + ":earn";
    }

    private static String redeemRef(String reference) {
        return reference == null ? null : reference + ":redeem";
    }

    private Merchant resolveMerchant(UUID organizerUuid) {
        return merchants.findByOrganizerUuid(organizerUuid)
                .orElseGet(() -> createOrganizerMerchant(organizerUuid));
    }

    private Merchant createOrganizerMerchant(UUID organizerUuid) {
        Merchant m = new Merchant();
        m.setTenantId(TICKETING_TENANT_ID);
        m.setOrganizerUuid(organizerUuid);
        m.setName("Organizer " + organizerUuid);
        m.setCategory("TICKETING");
        m.setCurrency(cellCurrency);
        try {
            Merchant saved = merchants.saveAndFlush(m);
            createDefaultEarnRule(saved.getId());
            log.info("Provisioned ticketing merchant {} for organizer {}", saved.getId(), organizerUuid);
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Lost the uk_merchant_organizer race — the winner's merchant is canonical.
            return merchants.findByOrganizerUuid(organizerUuid).orElseThrow(() -> race);
        }
    }

    private void createDefaultEarnRule(UUID merchantId) {
        LoyaltyRule r = new LoyaltyRule();
        r.setTenantId(TICKETING_TENANT_ID);
        r.setMerchantId(merchantId);
        r.setTransactionType(TransactionType.PURCHASE);
        r.setPointsPerUnit(defaultEarnRate);
        r.setMultiplier(BigDecimal.ONE);
        r.setActive(true);
        rules.save(r);
    }

    private static void requireOrganizer(UUID organizerUuid) {
        if (organizerUuid == null) {
            throw LoyaltyException.badRequest("ORGANIZER_REQUIRED", "organizerUuid is required");
        }
    }

    private static void requirePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw LoyaltyException.badRequest("PHONE_REQUIRED", "phoneNumber is required");
        }
    }
}
