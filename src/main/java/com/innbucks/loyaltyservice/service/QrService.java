package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.CryptoSigner;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class QrService {

    private final QrTokenRepository qrs;
    private final TransactionService transactionService;
    private final TransferService transferService;
    private final FraudService fraud;
    private final UserService userService;
    private final MerchantAuthz merchantAuthz;
    private final StaffRegistry staffRegistry;
    // The same operator switches as the typed-phone earn guards
    // (EARN_SELF_BLOCK / EARN_STAFF_RECIPIENT_BLOCK), so one flag turns a guard
    // off everywhere it runs. Absent earn config reads as ON: fail closed.
    private final boolean selfBlock;
    private final boolean staffRecipientBlock;
    private final CryptoSigner signer;
    private final int defaultTtl;

    @org.springframework.beans.factory.annotation.Value("${innbucks.currency:USD}")
    private String cellCurrency;


    private final com.innbucks.loyaltyservice.config.SupportedCurrencies supportedCurrencies;

    public QrService(QrTokenRepository qrs, TransactionService transactionService,
                     TransferService transferService, FraudService fraud,
                     UserService userService, MerchantAuthz merchantAuthz,
                     StaffRegistry staffRegistry,
                     LoyaltyProperties props,
                     com.innbucks.loyaltyservice.config.SupportedCurrencies supportedCurrencies) {
        this.qrs = qrs;
        this.transactionService = transactionService;
        this.transferService = transferService;
        this.fraud = fraud;
        this.userService = userService;
        this.merchantAuthz = merchantAuthz;
        this.staffRegistry = staffRegistry;
        this.selfBlock = props.earn() == null || props.earn().selfBlock();
        this.staffRecipientBlock = props.earn() == null || props.earn().staffRecipientBlock();
        this.signer = new CryptoSigner(props.qr().secret());
        this.defaultTtl = props.qr().ttlSeconds();
        this.supportedCurrencies = supportedCurrencies;
    }

    public Dtos.QrPayload issue(UUID tenantId, Dtos.QrIssueRequest req) {
        // --- Authorization: who may mint THIS token? ---
        // Without this a CUSTOMER could self-issue a MERCHANT-sourced QR for any
        // merchant + arbitrary amount and then /consume it to mint points from
        // nothing (the token is server-signed, so it verifies). Gate by source:
        if (req.sourceType() == QrToken.SourceType.MERCHANT) {
            // A points-awarding merchant QR may only be minted by staff who
            // administer that merchant. Also confirms the merchant is in-tenant.
            merchantAuthz.requireCallerAdministersMerchant(tenantId, req.sourceId());
        } else { // USER — a P2P transfer QR; sourceId is the SENDER's wallet.
            // You may only draft a transfer that debits your OWN wallet. Strict
            // ownership (no admin bypass) so nobody can mint a QR that drains
            // another user's balance.
            LoyaltyUser sender = userService.require(tenantId, req.sourceId());
            userService.requireCallerOwns(sender);
        }
        // A QR encodes a fixed value; a negative amount is never valid and would
        // otherwise flow into the transfer/earn as a sign-flipped credit.
        if (req.amount() != null && req.amount().signum() < 0) {
            throw LoyaltyException.badRequest("INVALID_AMOUNT", "amount must not be negative");
        }

        QrToken q = new QrToken();
        q.setTenantId(tenantId);
        if (req.sourceType() == QrToken.SourceType.MERCHANT) {
            // The till that showed the QR. Its earn is attributed here at
            // consume, because the scanning customer's token carries no shop.
            // The claim belongs to the QR's merchant: shop staff were pinned to
            // their token's merchant by the authz check above.
            q.setShopId(CallerDetails.currentShopId());
        }
        q.setSourceType(req.sourceType());
        q.setSourceId(req.sourceId());
        q.setTransactionType(req.transactionType());
        q.setAmount(req.amount());
        // Allowlist-validated (fail closed) — a QR encodes a money value; an
        // unknown currency code must never be minted into a signed token.
        q.setCurrency(supportedCurrencies.requireSupported(
                req.currency() != null ? req.currency() : cellCurrency));
        int ttl = req.ttlSeconds() == null ? defaultTtl : Math.max(30, req.ttlSeconds());
        q.setExpiresAt(Instant.now().plusSeconds(ttl));
        q.setToken(CryptoSigner.randomToken(24));
        q.setSignature(signer.sign(payload(q)));
        qrs.save(q);
        return new Dtos.QrPayload(q.getToken(), q.getSignature(),
                q.getTenantId().toString(), q.getSourceType().name(),
                q.getSourceId().toString(), q.getTransactionType().name(),
                q.getExpiresAt());
    }

    private String payload(QrToken q) {
        return q.getTenantId() + "|" + q.getSourceType() + "|" + q.getSourceId()
                + "|" + q.getTransactionType() + "|" + q.getToken()
                + "|" + (q.getAmount() == null ? "" : q.getAmount().toPlainString())
                + "|" + q.getExpiresAt().toEpochMilli();
    }

    /**
     * A merchant QR is the till's "scan to earn" code, shown on the counter for
     * the CUSTOMER to scan. The staff behind that counter can scan it too, and
     * {@code requireCallerOwns} is satisfied because they are crediting their
     * own account — so without this, the cashier earns on every sale a customer
     * does not claim. {@code QR_PRESENCE} is exempt from the typed-phone guards
     * precisely because the scanner is the customer; this is where that
     * assumption is checked instead of assumed.
     *
     * <ul>
     *   <li>{@code SELF_EARN} — the caller's token is scoped to this merchant
     *       (a SHOP_USER / SHOP_ADMIN of the QR's merchant).</li>
     *   <li>{@code STAFF_RECIPIENT} — the credited phone belongs to a staff
     *       member of this merchant. Catches the same person scanning with a
     *       plain customer token, which carries no merchant claim at all.
     *       {@link StaffRegistry} fails open when user-service is unreachable,
     *       the same trade the typed-phone guard makes.</li>
     * </ul>
     *
     * Both run before the token is marked used, so a refusal leaves it
     * consumable by the customer it was shown to (the throw rolls back; the
     * fraud row is written REQUIRES_NEW and survives).
     */
    private void requireNotStaffOfIssuingMerchant(UUID tenantId, UUID merchantId, LoyaltyUser recipient) {
        UUID callerMerchant = com.innbucks.loyaltyservice.security.CallerDetails.currentMerchantId();
        if (selfBlock && callerMerchant != null && callerMerchant.equals(merchantId)) {
            fraud.record(tenantId, recipient.getId(), merchantId, null,
                    FraudAttempt.Reason.SELF_EARN, "merchant QR consumed by the merchant's own staff token",
                    null, null);
            throw LoyaltyException.forbidden("SELF_EARN",
                    "You can't award points to your own account.");
        }
        if (staffRecipientBlock && staffRegistry.isStaffPhone(merchantId, recipient.getPhoneNumber())) {
            fraud.record(tenantId, recipient.getId(), merchantId, null,
                    FraudAttempt.Reason.STAFF_RECIPIENT, "merchant QR consumed by a staff member's phone",
                    null, null);
            throw LoyaltyException.forbidden("STAFF_RECIPIENT",
                    "Points can't be awarded to a staff account of this merchant.");
        }
    }

    /**
     * Pre-load the STAFF_RECIPIENT registry for the merchant whose QR is about
     * to be consumed, BEFORE {@link #consume} takes the QR row lock — the guard
     * runs under that lock, and a cold cache held it across a user-service
     * round-trip. A plain, non-locking read of the token; anything that does not
     * look like a live merchant QR of this tenant is simply skipped, so every
     * refusal (and its fraud evidence row) is still {@link #consume}'s own.
     * Never throws; fail-open semantics are the registry's, unchanged.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void prewarmStaffRecipientGuard(UUID tenantId, String token) {
        try {
            if (!staffRecipientBlock || tenantId == null || token == null || token.isBlank()) {
                return;
            }
            qrs.findByToken(token)
                    .filter(q -> tenantId.equals(q.getTenantId())
                            && q.getSourceType() == QrToken.SourceType.MERCHANT
                            && q.getUsedAt() == null)
                    .map(QrToken::getSourceId)
                    .filter(merchantId -> !staffRegistry.isCached(merchantId))
                    .ifPresent(staffRegistry::warm);
        } catch (RuntimeException e) {
            // An optimisation only — consume() loads on demand.
        }
    }

    public Dtos.TransactionResponse consume(UUID tenantId, Dtos.QrConsumeRequest req) {
        // --- Authorization: the credited/receiving user MUST be the caller. ---
        // consume() awards points (merchant QR) or receives a transfer (P2P QR)
        // TO req.userId(). Without binding that to the authenticated principal, a
        // caller could pass any/victim userId; combined with a merchant QR this is
        // a mint-to-anyone primitive. Strict ownership (no admin bypass): you scan,
        // you receive. This resolves + tenant-checks the user before touching the
        // token, so an unauthorized userId is rejected up front.
        LoyaltyUser recipient = userService.require(tenantId, req.userId());
        userService.requireCallerOwns(recipient);

        QrToken q = qrs.lockByToken(req.token())
                .orElseThrow(() -> {
                    fraud.record(tenantId, req.userId(), null, null,
                            FraudAttempt.Reason.INVALID_CODE, "qr token not found",
                            null, null);
                    return unknownQr();
                });
        if (!q.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "QR belongs to a different tenant");
        }
        if (!signer.verify(payload(q), req.signature())) {
            fraud.record(tenantId, req.userId(), null, null,
                    FraudAttempt.Reason.QR_BAD_SIGNATURE, "qr signature mismatch", null, null);
            throw LoyaltyException.forbidden("BAD_SIGNATURE", "This QR code couldn't be verified.");
        }
        if (q.getUsedAt() != null) {
            fraud.record(tenantId, req.userId(), null, null,
                    FraudAttempt.Reason.QR_REUSED, "qr already used", null, null);
            throw LoyaltyException.conflict("QR_REUSED", "This QR code has already been used.");
        }
        if (Instant.now().isAfter(q.getExpiresAt())) {
            fraud.record(tenantId, req.userId(), null, null,
                    FraudAttempt.Reason.QR_EXPIRED, "qr expired", null, null);
            throw LoyaltyException.badRequest("QR_EXPIRED", "This QR code has expired.");
        }
        if (q.getSourceType() == QrToken.SourceType.MERCHANT) {
            requireNotStaffOfIssuingMerchant(tenantId, q.getSourceId(), recipient);
        }
        q.setUsedAt(Instant.now());

        if (q.getSourceType() == QrToken.SourceType.MERCHANT) {
            // Merchant-issued QR awards points to the scanning user, attributed
            // to the ISSUING till's shop (stored at issue). Never the scanner's
            // shop claim: the scanner is the customer, who has none, and that is
            // how every QR earn used to land with a null shop.
            Dtos.TransactionResponse earned = transactionService.postForShop(tenantId, q.getSourceId(),
                    new Dtos.TransactionRequest(
                            null, req.userId(), null, q.getTransactionType(),
                            q.getAmount() == null ? BigDecimal.ZERO : q.getAmount(),
                            q.getCurrency(), req.reference()),
                    q.getShopId(),
                    com.innbucks.loyaltyservice.entity.EarnChannel.QR_PRESENCE);
            // What the scan produced, for the issuer's POST /qr/status. Written
            // on the locked row in the same transaction, so it commits with
            // used_at or not at all.
            q.setTransactionId(earned.id());
            q.setPointsAwarded(earned.pointsDelta());
            return earned;
        } else {
            // User-issued QR initiates a P2P transfer; sourceId is the sender.
            // Skip the caller-ownership check on the transfer: the CALLER here is
            // the recipient scanning the code, not the sender — but issue() already
            // required the sender to own the source before this single-use signed
            // token could exist, so the token is the sender's authorization.
            BigDecimal points = q.getAmount() == null ? BigDecimal.ZERO : q.getAmount();
            transferService.transfer(tenantId, new Dtos.TransferRequest(
                    q.getSourceId(), req.userId(), null, points, "qr-transfer"), false);
            q.setPointsAwarded(points);
            return new Dtos.TransactionResponse(null, TransactionType.TRANSFER, points,
                    points, null, null, null, null,
                    com.innbucks.loyaltyservice.security.CallerDetails.currentUserId(), null,
                    // invoiceId is null: this response is built in-flight, before
                    // any billing period containing it has been invoiced.
                    req.reference(), Instant.now(), null,
                    // A P2P transfer moves POINTS, not money — there is no
                    // transacted currency amount, so no currency and no USD
                    // base value. Null here means "not a money leg", which is
                    // why it must never be rendered as a zero amount.
                    null, null);
        }
    }

    /**
     * Where a QR token is: PENDING, CONSUMED or EXPIRED — so the till that showed
     * a QR can tell it was scanned. {@code POST /loyalty/qr/status}, the token in
     * the BODY (it is a consumable credential; URLs are logged).
     *
     * <p>Who may see it is who could have issued it: a MERCHANT QR needs
     * {@link MerchantAuthz#requireCallerAdministersMerchant} on the QR's merchant
     * (shop staff by their token's merchant, a merchant admin by organization,
     * SUPER_ADMIN always); a USER (transfer) QR needs its sender, strictly — no
     * admin bypass. Everything else — an unknown token, one of another tenant,
     * one the caller may not see — is the SAME 404 consume answers for an
     * unknown token, so the endpoint is not an existence oracle. A read: no
     * fraud row (the token is 192 random bits, so there is nothing to guess).
     */
    @Transactional(readOnly = true)
    public Dtos.QrStatusResponse status(UUID tenantId, String token) {
        QrToken q = qrs.findByToken(token)
                .filter(t -> tenantId.equals(t.getTenantId()))
                .orElseThrow(QrService::unknownQr);
        if (!callerMaySee(tenantId, q)) {
            throw unknownQr();
        }
        Dtos.QrStatus status;
        if (q.getUsedAt() != null) {
            status = Dtos.QrStatus.CONSUMED;
        } else if (Instant.now().isAfter(q.getExpiresAt())) {
            // The same test consume() applies, so the two never disagree.
            status = Dtos.QrStatus.EXPIRED;
        } else {
            status = Dtos.QrStatus.PENDING;
        }
        return new Dtos.QrStatusResponse(status, q.getExpiresAt(), q.getUsedAt(),
                q.getTransactionId(), q.getPointsAwarded());
    }

    private boolean callerMaySee(UUID tenantId, QrToken q) {
        try {
            if (q.getSourceType() == QrToken.SourceType.MERCHANT) {
                merchantAuthz.requireCallerAdministersMerchant(tenantId, q.getSourceId());
                return true;
            }
            LoyaltyUser sender = userService.require(tenantId, q.getSourceId());
            String callerPhone = CallerDetails.currentPhoneNumber();
            return callerPhone != null && callerPhone.equals(sender.getPhoneNumber());
        } catch (LoyaltyException refused) {
            // Not theirs (or the source is gone): indistinguishable from unknown.
            return false;
        }
    }

    /** consume's answer for an unknown token, shared with {@link #status}. */
    private static LoyaltyException unknownQr() {
        return new LoyaltyException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND",
                "This QR code is invalid or has expired.");
    }
}
