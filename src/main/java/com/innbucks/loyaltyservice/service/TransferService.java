package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class TransferService {

    private final UserService users;
    private final WalletService walletService;
    private final LoyaltyTransactionRepository transactions;
    private final MerchantRepository merchants;
    private final com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier;

    public TransferService(UserService users, WalletService walletService,
                           LoyaltyTransactionRepository transactions,
                           MerchantRepository merchants,
                           com.innbucks.loyaltyservice.integration.MemberActivityNotifier memberNotifier) {
        this.users = users;
        this.walletService = walletService;
        this.transactions = transactions;
        this.merchants = merchants;
        this.memberNotifier = memberNotifier;
    }

    /**
     * P2P transfer initiated directly by the wallet owner
     * ({@code POST /loyalty/transfer}). The caller MUST own the sender wallet —
     * see the {@code enforceCallerOwnership} overload for why there is no admin
     * bypass here.
     */
    public BigDecimal transfer(UUID tenantId, Dtos.TransferRequest req) {
        return transfer(tenantId, req, true);
    }

    /**
     * @param enforceCallerOwnership when true, the authenticated caller must BE
     *        the sender (strict ownership, no admin bypass). Pass false ONLY from
     *        a path that has already established the sender's authorization by
     *        another trust boundary — today just the P2P transfer-QR consume, where
     *        {@code QrService.issue} already required the sender to own the source
     *        (strict) before the single-use, HMAC-signed token could be minted, so
     *        the token itself is the sender's consent and the recipient who scans
     *        it is legitimately not the sender.
     *
     *        <p><b>Why no admin bypass on the direct path.</b> This used to call
     *        {@code requireCallerOwnsOrIsAdmin}, which let a MERCHANT_ADMIN /
     *        SHOP_ADMIN transfer FROM any customer's wallet to an
     *        attacker-controlled phone — a wallet-drain, and exactly the hijack
     *        {@code UserService.requireCallerOwns} warns about (the QR transfer
     *        path already used the strict check for this reason). Genuine ops
     *        corrections go through a manual ADJUSTMENT, which is capped and
     *        audited; a silent admin-initiated transfer to an arbitrary recipient
     *        is not a sanctioned tool.
     */
    public BigDecimal transfer(UUID tenantId, Dtos.TransferRequest req, boolean enforceCallerOwnership) {
        if (req.points() == null || req.points().signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_AMOUNT", "Please enter an amount greater than zero.");
        }
        // Recipient may be a UUID (registered) or a phone (auto-enrol as PENDING).
        boolean hasToUserId = req.toUserId() != null;
        boolean hasToPhone = req.toPhone() != null && !req.toPhone().isBlank();
        if (hasToUserId == hasToPhone) {
            throw LoyaltyException.badRequest("RECIPIENT_REQUIRED",
                    "supply exactly one of toUserId or toPhone");
        }

        var sender = users.require(tenantId, req.fromUserId());
        // Senders cannot be PENDING — you must be registered to spend.
        users.requireSpendable(sender);
        // The caller must OWN the sender wallet — strictly, with no admin bypass
        // (see the overload's javadoc). The QR consume path passes
        // enforceCallerOwnership=false because QrService.issue already proved the
        // sender's ownership when the token was minted.
        if (enforceCallerOwnership) {
            users.requireCallerOwns(sender);
        }

        var recipient = hasToUserId
                ? users.require(tenantId, req.toUserId())
                : users.findOrCreatePending(tenantId, req.toPhone(), sender.getMerchantId());

        if (sender.getId().equals(recipient.getId())) {
            throw LoyaltyException.badRequest("SELF_TRANSFER", "You can't transfer points to yourself.");
        }

        UUID merchantContext = sender.getMerchantId() != null
                ? sender.getMerchantId()
                : merchants.findByTenantId(tenantId).stream().findFirst()
                    .map(m -> m.getId())
                    .orElseThrow(() -> LoyaltyException.badRequest("NO_MERCHANT_CONTEXT",
                            "tenant has no merchant configured"));

        Wallet from = walletService.mainWallet(sender.getPhoneNumber());
        Wallet to = walletService.mainWallet(recipient.getPhoneNumber());
        // Wallets are global per phone, so two LoyaltyUser projections for the
        // same customer resolve to one wallet — block that as a self-transfer.
        if (from.getId().equals(to.getId())) {
            throw LoyaltyException.badRequest("SELF_TRANSFER", "You can't transfer points to yourself.");
        }

        // Sanitize the caller-supplied transfer note once; it is persisted as the
        // reference on both the debit and credit ledger rows (stored-XSS hardening).
        String reason = HtmlSanitizer.stripAll(req.reason());

        LoyaltyTransaction debit = new LoyaltyTransaction();
        // Attribution (V32): both legs carry the initiating caller.
        debit.setPostedBy(com.innbucks.loyaltyservice.security.CallerDetails.currentUserId());
        debit.setTenantId(tenantId);
        debit.setMerchantId(merchantContext);
        debit.setUserId(sender.getId());
        debit.setType(TransactionType.TRANSFER);
        debit.setPointsDelta(req.points().negate());
        debit.setReference(reason);
        transactions.save(debit);

        LoyaltyTransaction credit = new LoyaltyTransaction();
        credit.setPostedBy(com.innbucks.loyaltyservice.security.CallerDetails.currentUserId());
        credit.setTenantId(tenantId);
        credit.setMerchantId(merchantContext);
        credit.setUserId(recipient.getId());
        credit.setType(TransactionType.TRANSFER);
        credit.setPointsDelta(req.points());
        credit.setReference(reason);
        transactions.save(credit);

        // Lock wallets in canonical UUID order to avoid deadlocks when two
        // transfers between the same pair race in opposite directions.
        if (from.getId().compareTo(to.getId()) < 0) {
            walletService.apply(from.getId(), req.points().negate(), debit.getId(), "transfer-out", tenantId);
            walletService.apply(to.getId(), req.points(), credit.getId(), "transfer-in", tenantId);
        } else {
            walletService.apply(to.getId(), req.points(), credit.getId(), "transfer-in", tenantId);
            walletService.apply(from.getId(), req.points().negate(), debit.getId(), "transfer-out", tenantId);
        }
        BigDecimal senderBalance = walletService.totalBalance(sender.getPhoneNumber());
        // Confirm to the sender and tell the recipient they received points.
        memberNotifier.notifyTransferSent(sender.getPhoneNumber(), req.points(), senderBalance);
        memberNotifier.notifyTransferReceived(recipient.getPhoneNumber(), req.points(),
                walletService.totalBalance(recipient.getPhoneNumber()));
        return senderBalance;
    }

}
