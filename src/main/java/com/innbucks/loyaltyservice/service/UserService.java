package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.util.MsisdnValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Manages the loyalty-side projection of a user. Identity (name, email,
// nationalId) lives in user-service — this service only stores foreign
// references plus loyalty-specific state. There is intentionally no public
// "create user" endpoint.
//
// Callers (TransactionService, VoucherService, TransferService, shop checkout,
// ticketing) reach a user via findOrCreatePending, which mints a projection for
// any phone whether or not anyone has proven they own it. findOrEnrol — which
// verifies against ticketing's user-service before minting ACTIVE — has no
// callers in src/main and must not be given one without deciding first whether
// ticketing is still the right identity authority; it is not, for customers who
// authenticate elsewhere.
//
// Whether a phone's owner has PROVEN they hold it is a phone-level fact in
// `phone_registrations` (V40), written only by registerPhone. LoyaltyUser.status
// caches it per projection.
@Service
@Transactional
public class UserService {

    private final LoyaltyUserRepository users;
    private final WalletRepository wallets;
    private final UserServiceClient userServiceClient;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;
    private final PhoneRegistrationRepository registrations;

    /** This cell's country pin (ISO-3166-1 alpha-2) — region hint for
     *  normalising an inbound phone to E.164. Defaults to ZW so plain-`new`
     *  unit tests get a sensible value; @Value overrides from INNBUCKS_COUNTRY. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    public UserService(LoyaltyUserRepository users,
                       WalletRepository wallets,
                       UserServiceClient userServiceClient,
                       com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics,
                       PhoneRegistrationRepository registrations) {
        this.users = users;
        this.wallets = wallets;
        this.userServiceClient = userServiceClient;
        this.metrics = metrics;
        this.registrations = registrations;
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
        // V40: PENDING means "nobody has proven this number", and that is a fact
        // about the PHONE. If it is already registered, a projection under a new
        // tenant is born ACTIVE — otherwise a customer who registered a year ago
        // is handed a fresh PENDING row the first time they shop at a new
        // merchant, and is refused at that till as though unregistered.
        LoyaltyUser.Status status = isPhoneRegistered(phoneNumber)
                ? LoyaltyUser.Status.ACTIVE
                : LoyaltyUser.Status.PENDING;
        return createWithWallet(tenantId, phoneNumber, merchantId, status);
    }

    /**
     * Has the owner of this phone proven they hold it? The single question the
     * spend gate and every projection-create asks (V40).
     *
     * <p>Expects an already-normalised E.164 phone — every caller inside this
     * service passes one through {@link #normalizePhone}.
     */
    public boolean isPhoneRegistered(String e164Phone) {
        return registrations.existsByPhoneNumberAndRevokedAtIsNull(e164Phone);
    }

    /**
     * True when this projection is PENDING <em>and</em> its phone is genuinely
     * unregistered — i.e. the customer really cannot spend.
     *
     * <p>The distinction matters because a PENDING row is no longer proof of
     * anything on its own: it may simply pre-date the registration, or have been
     * minted by a race. Callers gating a spend must ask this, never
     * {@code status == PENDING}.
     */
    public boolean isRegistrationPending(LoyaltyUser u) {
        return u.getStatus() == LoyaltyUser.Status.PENDING
                && !isPhoneRegistered(u.getPhoneNumber());
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
     *
     * <p>Every message here reaches a CUSTOMER — a cashier reads it off the till
     * or the app renders it verbatim — so all four branches stay in the same
     * plain second-person register. PENDING additionally says that points keep
     * accruing, because "you can't spend yet" without "you're still earning"
     * reads as though the balance were lost.
     */
    public void requireSpendable(LoyaltyUser u) {
        switch (u.getStatus()) {
            case ACTIVE -> { /* ok */ }
            // V40: PENDING is now a CACHE of a phone-level fact, so this branch
            // consults the fact before refusing. When the phone is registered
            // the row is stale — heal it here and let the spend through.
            //
            // Healing inside the caller's transaction means it rolls back with a
            // failed spend (INSUFFICIENT_FUNDS, say). That is deliberate: the
            // heal is an optimisation, not the mechanism. The sweeper's heal arm
            // converges anything left behind, and the next attempt re-heals.
            //
            // The copy names NO customer action, deliberately. It told the holder
            // to "finish signing up", which was accurate only while every customer
            // reached us through ticketing's OTP registration. Customers who
            // authenticate elsewhere have no such screen, and until their proof
            // reaches us the only honest statement is that setup is incomplete on
            // our side.
            case PENDING -> {
                if (!isPhoneRegistered(u.getPhoneNumber())) {
                    throw LoyaltyException.forbidden("USER_PENDING",
                            "Your rewards account is still being set up, so these points can't be spent "
                                    + "yet. You'll keep earning in the meantime.");
                }
                u.setStatus(LoyaltyUser.Status.ACTIVE);
                metrics.incPendingPromoted(1);
            }
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
        // Stamped so registerPhone can tell this apart from a sweeper age-out
        // and leave it alone: a deliberate deactivation must not be undone by
        // the customer simply logging in again.
        u.setStatusReason(LoyaltyUser.StatusReason.OPERATOR);
        return u;
    }

    public LoyaltyUser block(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        u.setStatus(LoyaltyUser.Status.BLOCKED);
        return u;
    }

    /**
     * Lifts a fraud hold, returning the account to ACTIVE.
     *
     * <p>Until this existed, nothing in the service ever transitioned a row OUT
     * of BLOCKED — {@code FraudService}'s velocity rule could set it and there
     * was no way back except hand-written SQL against production. That was
     * survivable only while the block was believed to be self-inflicted; it was
     * not (any caller could block any account by naming its id), so a remedy is
     * part of fixing that, not a separate feature.
     *
     * <p>Refuses anything that is not actually blocked, rather than silently
     * flipping it to ACTIVE: a PENDING account is unproven and an INACTIVE one
     * was aged out or deactivated, and neither is a fraud hold to lift. Turning
     * this into a general "make the account active" lever is exactly how it
     * would end up used to bypass those.
     */
    public LoyaltyUser unblock(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        if (u.getStatus() != LoyaltyUser.Status.BLOCKED) {
            throw LoyaltyException.conflict("USER_NOT_BLOCKED",
                    "This account is not blocked (status " + u.getStatus() + ").");
        }
        u.setStatus(LoyaltyUser.Status.ACTIVE);
        // Clear the reason too. FraudService blocks any row that is not already
        // BLOCKED — including one sitting at INACTIVE/OPERATOR — so a hold can be
        // stamped over a deactivation, and lifting it without clearing would
        // leave an ACTIVE row still claiming an operator deactivated it. Nothing
        // reads statusReason on an ACTIVE row today, which is exactly why a stale
        // one would survive long enough to mislead whoever reads it first.
        u.setStatusReason(null);
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
        return registerPhone(phoneNumber, PhoneRegistration.Source.TICKETING_OTP, null, null, null)
                .projectionsPromoted();
    }

    /**
     * Outcome of {@link #registerPhone}. {@code newlyRegistered} is false on a
     * replay or a second proof for a phone already registered;
     * {@code projectionsPromoted} counts rows whose status actually changed, so
     * a caller can stay silent on a no-op instead of re-notifying a customer
     * every time they log in.
     */
    public record RegistrationResult(boolean newlyRegistered, int projectionsPromoted, boolean replay) {}

    /**
     * Records that the owner of {@code phoneNumber} has PROVEN they hold it, and
     * brings every projection of that phone into line (V40).
     *
     * <p>This is the one write path for the phone-level fact. Both proofs go
     * through it — ticketing's OTP webhook via {@link #promoteByPhone}, and the
     * partner endpoint the app's middleware calls — so there is a single place
     * where "what counts as proven" is decided, and a single place to audit.
     *
     * <p>What it moves, and what it deliberately does not:
     * <ul>
     *   <li>PENDING → ACTIVE. The whole point.</li>
     *   <li>INACTIVE + {@code PENDING_EXPIRED} → ACTIVE. An age-out is the
     *       sweeper saying "nobody ever proved this"; a proof answers exactly
     *       that. Without this an app customer who waited 90 days for a signal
     *       that did not exist stays unspendable forever.</li>
     *   <li><b>BLOCKED is never touched.</b> That is a fraud hold, and proving
     *       you own a number says nothing about the hold.</li>
     *   <li><b>INACTIVE + {@code OPERATOR} is never touched.</b> A human took
     *       this account out of the programme on purpose; a login must not
     *       silently undo them.</li>
     * </ul>
     *
     * <p>Replay: {@code assertedAt} is monotonic per phone. An assertion not
     * strictly newer than the last one recorded is a replay — the caller
     * retried, or an old token was re-sent — and returns without side effects.
     * Callers passing null (the ticketing webhook, which has no such token)
     * always proceed; the row-level work is idempotent anyway.
     */
    public RegistrationResult registerPhone(String phoneNumber,
                                            PhoneRegistration.Source source,
                                            String sourceRef,
                                            Instant assertedAt,
                                            String assertionJti) {
        String phone = normalizePhone(phoneNumber);

        // Serialise concurrent proofs for one phone (two devices logging in, or
        // an app assertion racing ticketing's webhook) so they cannot both
        // insert. A row that appears between this lock and the insert surfaces
        // as a PK violation, which means someone else won the race — the same
        // outcome we wanted.
        Optional<PhoneRegistration> existing = registrations.lockByPhoneNumber(phone);
        boolean newlyRegistered = existing.isEmpty();

        PhoneRegistration reg = existing.orElseGet(() -> {
            PhoneRegistration fresh = new PhoneRegistration();
            fresh.setPhoneNumber(phone);
            fresh.setRegisteredAt(Instant.now());
            return fresh;
        });

        if (!newlyRegistered && assertedAt != null
                && reg.getLastAssertedAt() != null
                && !assertedAt.isAfter(reg.getLastAssertedAt())) {
            return new RegistrationResult(false, 0, true);
        }

        reg.setSource(source);
        if (sourceRef != null && !sourceRef.isBlank()) {
            reg.setSourceRef(sourceRef);
        }
        if (assertedAt != null) {
            reg.setLastAssertedAt(assertedAt);
            reg.setLastAssertionJti(assertionJti);
        }
        // A fresh proof reinstates a revoked registration. Revocation answers a
        // compromised credential, not a bad customer, so the customer proving
        // themselves again through a sound channel is the intended recovery.
        reg.setRevokedAt(null);
        reg.setRevokedReason(null);
        registrations.save(reg);

        int promoted = 0;
        for (LoyaltyUser u : users.findByPhoneNumber(phone)) {
            if (u.getStatus() == LoyaltyUser.Status.PENDING) {
                u.setStatus(LoyaltyUser.Status.ACTIVE);
                u.setStatusReason(null);
                promoted++;
            } else if (u.getStatus() == LoyaltyUser.Status.INACTIVE
                    && u.getStatusReason() == LoyaltyUser.StatusReason.PENDING_EXPIRED) {
                u.setStatus(LoyaltyUser.Status.ACTIVE);
                u.setStatusReason(null);
                promoted++;
            }
        }
        metrics.incPendingPromoted(promoted);
        metrics.incPhoneRegistered(source.name());
        return new RegistrationResult(newlyRegistered, promoted, false);
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
