package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.QrTokenRepository;
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
    private final CryptoSigner signer;
    private final int defaultTtl;

    @org.springframework.beans.factory.annotation.Value("${innbucks.currency:USD}")
    private String cellCurrency;


    public QrService(QrTokenRepository qrs, TransactionService transactionService,
                     TransferService transferService, FraudService fraud,
                     UserService userService, MerchantAuthz merchantAuthz,
                     LoyaltyProperties props) {
        this.qrs = qrs;
        this.transactionService = transactionService;
        this.transferService = transferService;
        this.fraud = fraud;
        this.userService = userService;
        this.merchantAuthz = merchantAuthz;
        this.signer = new CryptoSigner(props.qr().secret());
        this.defaultTtl = props.qr().ttlSeconds();
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
        q.setSourceType(req.sourceType());
        q.setSourceId(req.sourceId());
        q.setTransactionType(req.transactionType());
        q.setAmount(req.amount());
        q.setCurrency(req.currency() != null ? req.currency() : cellCurrency);
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
                    return new LoyaltyException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "This QR code is invalid or has expired.");
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
        q.setUsedAt(Instant.now());

        if (q.getSourceType() == QrToken.SourceType.MERCHANT) {
            // Merchant-issued QR awards points to the scanning user.
            return transactionService.post(tenantId, q.getSourceId(), new Dtos.TransactionRequest(
                    null, req.userId(), null, q.getTransactionType(),
                    q.getAmount() == null ? BigDecimal.ZERO : q.getAmount(),
                    q.getCurrency(), req.reference()));
        } else {
            // User-issued QR initiates a P2P transfer; sourceId is the sender.
            BigDecimal points = q.getAmount() == null ? BigDecimal.ZERO : q.getAmount();
            transferService.transfer(tenantId, new Dtos.TransferRequest(
                    q.getSourceId(), req.userId(), null, points, "qr-transfer"));
            return new Dtos.TransactionResponse(null, TransactionType.TRANSFER, points,
                    points, null, null, null, null, req.reference(), Instant.now());
        }
    }
}
