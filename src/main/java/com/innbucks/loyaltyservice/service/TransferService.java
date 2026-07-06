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

    public BigDecimal transfer(UUID tenantId, Dtos.TransferRequest req) {
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
        // Caller must own the sender wallet. Admins (SUPER_ADMIN, MERCHANT_ADMIN,
        // SHOP_ADMIN) can transfer on behalf of a user — useful for ops and
        // customer-support reversals. CUSTOMER must be the wallet owner.
        users.requireCallerOwnsOrIsAdmin(sender);

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
        debit.setTenantId(tenantId);
        debit.setMerchantId(merchantContext);
        debit.setUserId(sender.getId());
        debit.setType(TransactionType.TRANSFER);
        debit.setPointsDelta(req.points().negate());
        debit.setReference(reason);
        transactions.save(debit);

        LoyaltyTransaction credit = new LoyaltyTransaction();
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
