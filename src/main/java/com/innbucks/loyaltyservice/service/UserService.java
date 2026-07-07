package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.util.MsisdnValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Manages the loyalty-side projection of a user. Identity (name, email,
// nationalId) lives in user-service — this service only stores foreign
// references plus loyalty-specific state. There is intentionally no public
// "create user" endpoint: callers (TransactionService, VoucherService, QR
// flows) reach a user via {@link #findOrEnrol(UUID, String, UUID)} which
// validates the phone number against user-service first.
@Service
@Transactional
public class UserService {

    private final LoyaltyUserRepository users;
    private final WalletRepository wallets;
    private final UserServiceClient userServiceClient;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;

    /** This cell's country pin (ISO-3166-1 alpha-2) — region hint for
     *  normalising an inbound phone to E.164. Defaults to ZW so plain-`new`
     *  unit tests get a sensible value; @Value overrides from INNBUCKS_COUNTRY. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    public UserService(LoyaltyUserRepository users,
                       WalletRepository wallets,
                       UserServiceClient userServiceClient,
                       com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics) {
        this.users = users;
        this.wallets = wallets;
        this.userServiceClient = userServiceClient;
        this.metrics = metrics;
    }

    // Idempotent enrolment: returns the existing LoyaltyUser for the
    // (tenant, phone) pair, or creates one after validating the phone
    // number resolves to a real customer in user-service. Use this when the
    // recipient is known to be registered (e.g. explicit enrolment flow).
    public LoyaltyUser findOrEnrol(UUID tenantId, String phoneNumber, UUID merchantId) {
        phoneNumber = normalizePhone(phoneNumber);
        Optional<LoyaltyUser> existing = users.findByTenantIdAndPhoneNumber(tenantId, phoneNumber);
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<CustomerTierResponseDTO> verified = userServiceClient.getCustomerTier(phoneNumber);
        if (verified.isEmpty()) {
            throw LoyaltyException.notFound(
                    "user-service has no customer with phone " + phoneNumber);
        }
        return createWithWallet(tenantId, phoneNumber, merchantId, LoyaltyUser.Status.ACTIVE);
    }

    /**
     * Phone-keyed wallet entry-point: returns the existing LoyaltyUser, or
     * creates a {@link LoyaltyUser.Status#PENDING} row when the recipient hasn't
     * registered yet. Used by issuance / transfer flows that want to credit a
     * phone whether or not user-service has heard of it.
     *
     * <p>Accrual works against a PENDING user (transactions, vouchers, P2P
     * receives); redemption does not — that gate lives in the downstream
     * services so the policy is enforced at the spend path, not at lookup.
     */
    public LoyaltyUser findOrCreatePending(UUID tenantId, String phoneNumber, UUID merchantId) {
        phoneNumber = normalizePhone(phoneNumber);
        Optional<LoyaltyUser> existing = users.findByTenantIdAndPhoneNumber(tenantId, phoneNumber);
        if (existing.isPresent()) {
            return existing.get();
        }
        return createWithWallet(tenantId, phoneNumber, merchantId, LoyaltyUser.Status.PENDING);
    }

    private LoyaltyUser createWithWallet(UUID tenantId, String phoneNumber, UUID merchantId,
                                         LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(tenantId);
        u.setMerchantId(merchantId);
        u.setPhoneNumber(phoneNumber);
        u.setStatus(status);
        users.save(u);

        // Ensure the customer's single GLOBAL MAIN wallet exists. Keyed by phone,
        // so a second LoyaltyUser for the same phone (different tenant) reuses the
        // one wallet rather than creating a per-tenant silo. Idempotent; the
        // uk_wallet_main partial unique index is the integrity backstop.
        if (wallets.findFirstByPhoneNumberAndType(phoneNumber, Wallet.Type.MAIN).isEmpty()) {
            Wallet main = new Wallet();
            main.setPhoneNumber(phoneNumber);
            main.setLabel("Main");
            main.setType(Wallet.Type.MAIN);
            wallets.save(main);
        }

        return u;
    }

    /**
     * Throws if the user can't perform a *spending* action right now. Use this
     * on every redemption / outgoing-transfer path so PENDING (not yet
     * registered) and BLOCKED (fraud) accounts can accrue but not spend.
     */
    public void requireSpendable(LoyaltyUser u) {
        switch (u.getStatus()) {
            case ACTIVE -> { /* ok */ }
            case PENDING -> throw LoyaltyException.forbidden("USER_PENDING",
                    "user has not completed registration; balance can be received but not spent");
            case BLOCKED -> throw LoyaltyException.forbidden("USER_BLOCKED", "Your account is currently suspended. Please contact support.");
            case INACTIVE -> throw LoyaltyException.forbidden("USER_INACTIVE", "Your account is inactive. Please contact support to reactivate it.");
        }
    }

    /**
     * Throws 403 NOT_WALLET_OWNER unless the caller is acting on their own
     * LoyaltyUser, OR holds an admin role (SUPER_ADMIN / MERCHANT_ADMIN /
     * SHOP_ADMIN) that's explicitly allowed to act on behalf of another user
     * (customer-support reversals, merchant-ops actions, etc.).
     *
     * <p>Used by every endpoint that accepts a {@code userId} from the URL or
     * body — without this check a logged-in CUSTOMER could drain or read any
     * other user's data simply by guessing or harvesting their UUID.
     */
    public void requireCallerOwnsOrIsAdmin(LoyaltyUser target) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth != null) {
            for (var ga : auth.getAuthorities()) {
                String role = ga.getAuthority();
                if ("ROLE_SUPER_ADMIN".equals(role)
                        || "ROLE_MERCHANT_ADMIN".equals(role)
                        || "ROLE_SHOP_ADMIN".equals(role)) {
                    return; // admin acting on behalf is allowed
                }
            }
        }
        String callerPhone = com.innbucks.loyaltyservice.security.CallerDetails.currentPhoneNumber();
        if (callerPhone == null || !callerPhone.equals(target.getPhoneNumber())) {
            throw LoyaltyException.forbidden("NOT_WALLET_OWNER",
                    "you can only act on your own loyalty account");
        }
    }

    /**
     * Strict owner check: the caller must be acting on their OWN loyalty account.
     * Unlike {@link #requireCallerOwnsOrIsAdmin}, admin roles do NOT bypass this.
     *
     * <p>Use where "acting on behalf of another user" has no legitimate meaning and
     * would be a minting/hijack vector — e.g. the user credited by consuming a QR
     * MUST be the scanning caller, and the sender of a P2P transfer-QR MUST be the
     * caller who drafted it. An admin bypass there would let a merchant admin (who
     * can self-issue a merchant QR) or any privileged caller move value to/from an
     * arbitrary account.
     */
    public void requireCallerOwns(LoyaltyUser target) {
        String callerPhone = com.innbucks.loyaltyservice.security.CallerDetails.currentPhoneNumber();
        if (callerPhone == null || !callerPhone.equals(target.getPhoneNumber())) {
            throw LoyaltyException.forbidden("NOT_WALLET_OWNER",
                    "you can only act on your own loyalty account");
        }
    }

    // Internal lifecycle hooks used by FraudService and admin flows. These
    // affect the LoyaltyUser's status within the loyalty program only — they
    // do NOT change the user's account state in user-service.
    public LoyaltyUser deactivate(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        u.setStatus(LoyaltyUser.Status.INACTIVE);
        return u;
    }

    public LoyaltyUser block(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        u.setStatus(LoyaltyUser.Status.BLOCKED);
        return u;
    }

    public LoyaltyUser require(UUID tenantId, UUID userId) {
        LoyaltyUser u = users.findById(userId)
                .orElseThrow(() -> LoyaltyException.notFound("user"));
        if (!u.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "user belongs to a different tenant");
        }
        return u;
    }

    /**
     * Resolve a LoyaltyUser by phone number within the caller's tenant. The
     * lookup is tenant-scoped by construction (the {@code uk_user_tenant_phone}
     * unique key), so — unlike {@link #require(UUID, UUID)} — there's no
     * cross-tenant branch: a phone that exists only under another tenant simply
     * returns empty → 404, which also avoids revealing that the number exists
     * elsewhere on the platform. The supplied phone is normalised to the stored
     * E.164 form first, so a caller passing {@code 0771234567} / {@code 771234567}
     * / {@code +263771234567} all resolve to the one row.
     */
    public LoyaltyUser requireByPhone(UUID tenantId, String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        return users.findByTenantIdAndPhoneNumber(tenantId, phone)
                .orElseThrow(() -> LoyaltyException.notFound("user"));
    }

    /**
     * Called by user-service via the {@code /loyalty/internal/users/promote}
     * webhook the moment a phone completes registration. Flips every
     * {@link LoyaltyUser.Status#PENDING} row matching that phone — across all
     * tenants — to {@link LoyaltyUser.Status#ACTIVE} so the receiver can now
     * spend whatever accrued while they were unregistered.
     *
     * <p>Idempotent. Already-ACTIVE rows are left alone; BLOCKED/INACTIVE rows
     * stay where they are (registration doesn't unblock fraud holds).
     *
     * @return count of rows promoted in this call.
     */
    public int promoteByPhone(String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        List<LoyaltyUser> matches = users.findByPhoneNumber(phone);
        int promoted = 0;
        for (LoyaltyUser u : matches) {
            if (u.getStatus() == LoyaltyUser.Status.PENDING) {
                u.setStatus(LoyaltyUser.Status.ACTIVE);
                promoted++;
            }
        }
        metrics.incPendingPromoted(promoted);
        return promoted;
    }

    /**
     * Canonicalise an inbound phone to E.164 ({@code +<cc><national>}) against
     * this cell's country. Every phone that enters the service — enrolment,
     * pending-create, by-phone lookup, and the registration-promote webhook —
     * passes through here, so the loyalty projection keys off the exact E.164
     * form user-service stores. Blank or unparseable is rejected 400 rather
     * than creating a wallet/user under a spelling nothing else will match.
     */
    private String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw LoyaltyException.badRequest("BAD_PHONE", "Please provide a phone number.");
        }
        return MsisdnValidator.normalizeToE164(raw, deploymentCountry)
                .orElseThrow(() -> LoyaltyException.badRequest("BAD_PHONE", "Invalid phone number: " + raw));
    }

    public static Dtos.UserResponse toResponse(LoyaltyUser u) {
        return new Dtos.UserResponse(u.getId(), u.getTenantId(), u.getPhoneNumber(),
                u.getRole().name(), u.getStatus().name());
    }
}
