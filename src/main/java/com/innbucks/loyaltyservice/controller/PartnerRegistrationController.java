package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.security.RegistrationAssertionVerifier;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The channel by which a trusted partner tells loyalty that the owner of a
 * phone number has PROVEN they hold it.
 *
 * <p><b>Why this exists.</b> Loyalty's only source of that proof was ticketing's
 * OTP signup webhook. Customers who authenticate somewhere else never walk that
 * flow, so their accounts sat PENDING forever: earning, receiving, unable to
 * spend. This endpoint is the missing edge, callable from outside the cluster by
 * the party that actually performed the phone verification.
 *
 * <h2>Four auth modes, and why the default is the signed one</h2>
 * <ul>
 *   <li>{@code assertion} (default) — the caller sends a short-lived,
 *       phone-scoped token signed with a private key it alone holds. Loyalty
 *       stores only the public key, so nothing here is worth stealing, and a
 *       captured assertion names one phone that was already registered.</li>
 *   <li>{@code key} — a shared secret in {@code X-Partner-Key}, for a caller
 *       that cannot sign. Honest about its weakness: whoever holds the key can
 *       register ANY phone, so it is opt-in, never the default, and the key must
 *       never reach a mobile client.</li>
 *   <li>{@code veengu} — <b>DEAD, never enable.</b> The customer's OWN Veengu
 *       session, validated against Veengu's {@code GET /auth/identity}. It was
 *       superseded once the partner's Postman collections showed the app
 *       authenticates against the InnBucks Client Service API rather than Veengu
 *       directly, so a Veengu access token is not what the app holds. Kept only
 *       because V41 is applied history.</li>
 *   <li>{@code innbucks} — <b>DEAD AND UNSOUND, never enable.</b> It sent the
 *       customer's {@code X-Innbucks-User-Token} plus a CLAIMED phone and asked
 *       the middleware to read that msisdn under that token, treating an answer
 *       as proof. That holds only if the platform refuses when the token does
 *       not own the number, and measurement showed it does not — the probe is a
 *       directory lookup that answers for any customer. Enabling it would let
 *       anyone holding any customer token register any other customer's phone
 *       and, since this is a {@link #selfServiceMode()}, receive a live loyalty
 *       session for it. See the CAUTION in {@code CLAUDE.md} and the
 *       {@code InnbucksSessionClient} javadoc for the evidence.</li>
 *   <li>{@code innbucks_validate} (V44) — an <b>ELIGIBILITY</b> mode, distinct
 *       in kind from every mode above: it does not claim to prove ownership at
 *       all. The platform owner's decision (2026-09) is that every InnBucks
 *       customer may spend loyalty points, so the body msisdn is checked
 *       against the app-authorized {@code /validate} directory endpoint and a
 *       confirmed customer is registered
 *       ({@code source = INNBUCKS_VALIDATE}). Anyone can name any customer's
 *       number here; the effect is only that the number's (already eligible)
 *       owner becomes spendable. What keeps that bounded is that this mode
 *       NEVER returns a session — the caller gains no power to act as the
 *       phone it named. See the V44 migration for the decision record.</li>
 * </ul>
 *
 * <p><b>The live registration paths are ticketing's OTP webhook</b>
 * ({@code source = TICKETING_OTP}), which reaches {@code registerPhone} through
 * {@code promoteByPhone}, <b>and — where a cell enables it — the
 * {@code innbucks_validate} mode here</b> (typically called by the app after
 * each middleware login, and by {@code InnbucksValidateBacklogSweeper} for the
 * pre-existing PENDING backlog). {@code assertion} and {@code key} remain
 * available for a partner BACKEND registering on a customer's behalf.
 *
 * <h2>The /validate endpoint: never an OWNERSHIP proof</h2>
 * {@code /auth/client-service/msisdn/{msisdn}/validate} is authorized by the
 * APP's own credentials and answers success for EVERY real InnBucks customer,
 * so it proves a number EXISTS, never that the caller holds it. That is why the
 * dead {@code innbucks} mode may never point its probe at it (the provisioning
 * check still refuses that), and why {@code innbucks_validate} — which uses it
 * deliberately, for eligibility — must never mint a session or feed any
 * identity decision. The two uses differ in the question asked, not in what the
 * endpoint answers.
 *
 * <h2>Fail-closed</h2>
 * Disabled (the default) answers 404 — indistinguishable from no such route.
 * Enabled but with no key material answers 503 and logs a boot-time
 * HALF-PROVISIONED error, so a half-finished rollout is loud rather than
 * silently refusing every customer.
 *
 * <p><b>This endpoint never takes an unsigned phone number in assertion mode.</b>
 * The phone comes from the signed {@code sub} claim. A body field would let
 * anyone holding one valid assertion register any number they liked.
 */
@RestController
@RequestMapping("/loyalty/partner")
@Slf4j
public class PartnerRegistrationController {

    private final UserService userService;
    private final RegistrationAssertionVerifier verifier;
    private final com.innbucks.loyaltyservice.client.VeenguIdentityClient veenguClient;
    private final com.innbucks.loyaltyservice.client.InnbucksSessionClient innbucksClient;
    private final com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient validateClient;
    private final com.innbucks.loyaltyservice.security.LoyaltySessionIssuer sessionIssuer;
    private final MemberActivityNotifier memberNotifier;
    private final LoyaltyMetrics metrics;
    private final boolean enabled;
    private final String authMode;
    private final String partnerKey;

    public PartnerRegistrationController(
            UserService userService,
            RegistrationAssertionVerifier verifier,
            com.innbucks.loyaltyservice.client.VeenguIdentityClient veenguClient,
            com.innbucks.loyaltyservice.client.InnbucksSessionClient innbucksClient,
            com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient validateClient,
            com.innbucks.loyaltyservice.security.LoyaltySessionIssuer sessionIssuer,
            MemberActivityNotifier memberNotifier,
            LoyaltyMetrics metrics,
            @Value("${loyalty.registration.partner.enabled:false}") boolean enabled,
            @Value("${loyalty.registration.partner.auth-mode:assertion}") String authMode,
            @Value("${loyalty.registration.partner.key:}") String partnerKey) {
        this.userService = userService;
        this.verifier = verifier;
        this.veenguClient = veenguClient;
        this.innbucksClient = innbucksClient;
        this.validateClient = validateClient;
        this.sessionIssuer = sessionIssuer;
        this.memberNotifier = memberNotifier;
        this.metrics = metrics;
        this.enabled = enabled;
        this.authMode = authMode == null ? "assertion" : authMode.trim().toLowerCase();
        this.partnerKey = partnerKey;
    }

    @PostMapping("/registrations")
    @Operation(summary = "Record that a phone's owner has proven they hold it",
            description = """
                    Records the fact that activates every loyalty projection of a phone, now and in \
                    future, in one of three live modes: `assertion` (default — a short-lived \
                    phone-scoped token a partner backend signed), `key` (a shared secret in \
                    `X-Partner-Key`), or `innbucks_validate` (the body msisdn is confirmed as a \
                    real InnBucks customer against the platform's own directory — an ELIGIBILITY \
                    check per the platform-owner decision that every InnBucks customer may spend \
                    points, not an ownership proof).

                    **No mode returns a session.** The two dead self-service modes (`innbucks`, \
                    `veengu`) are disabled, and `innbucks_validate` deliberately mints nothing: \
                    registering a phone makes its owner spendable but grants the caller no power \
                    to act as them. The customer app obtains its loyalty session from ticketing's \
                    OTP verify instead.

                    Idempotent and safe to call on every login: a repeat is a no-op that reports \
                    `projectionsPromoted: 0`. The body `phoneNumber` is read in `key` and \
                    `innbucks_validate` modes — in `assertion` mode the phone comes solely from \
                    the signed `sub`, so a caller cannot pair a valid assertion for its own \
                    number with someone else's.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registration recorded (or already present)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "First registration (self-service mode)", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Phone registration recorded",
                                              "data": {
                                                "phoneNumber": "+263771234567",
                                                "registered": true,
                                                "newlyRegistered": true,
                                                "projectionsPromoted": 2,
                                                "replay": false,
                                                "loyaltyToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3In0.sig",
                                                "expiresInSeconds": 43200
                                              }
                                            }"""),
                                    @ExampleObject(name = "Repeat login (no-op)", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Phone registration recorded",
                                              "data": {
                                                "phoneNumber": "+263771234567",
                                                "registered": true,
                                                "newlyRegistered": false,
                                                "projectionsPromoted": 0,
                                                "replay": false
                                              }
                                            }""")})),
            @ApiResponse(responseCode = "400", description = "BAD_PHONE — the asserted number is not a valid phone for this cell",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "BAD_PHONE",
                                      "message": "Invalid phone number: 07712345",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "401", description = "REGISTRATION_UNAUTHORIZED — assertion or key rejected. Deliberately opaque: the body never says which check failed.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "REGISTRATION_UNAUTHORIZED",
                                      "message": "Registration proof was not accepted.",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "Partner registration is not enabled on this deployment",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Partner registration is not enabled on this deployment not found",
                                      "data": null
                                    }"""))),
            @ApiResponse(responseCode = "503", description = "REGISTRATION_UNCONFIGURED — enabled but not provisioned (half-provisioned cell); "
                    + "or REGISTRATION_UPSTREAM_UNAVAILABLE — the upstream could not be reached. RETRYABLE: nothing was registered and the claim was not refused.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Half-provisioned", value = """
                                            {
                                              "code": "REGISTRATION_UNCONFIGURED",
                                              "message": "Partner registration is enabled but no credential is configured.",
                                              "data": null
                                            }"""),
                                    @ExampleObject(name = "Upstream unreachable (retry)", value = """
                                            {
                                              "code": "REGISTRATION_UPSTREAM_UNAVAILABLE",
                                              "message": "Registration could not be verified right now. Please try again.",
                                              "data": null
                                            }""")}))
    })
    public ResponseEntity<ApiResult<Map<String, Object>>> register(
            @RequestHeader(value = "X-Partner-Key", required = false) String presentedKey,
            @RequestHeader(value = "X-Veengu-Access-Token", required = false) String veenguToken,
            @RequestHeader(value = "X-Innbucks-User-Token", required = false) String innbucksUserToken,
            @RequestBody(required = false) PartnerRegistrationRequest body) {

        if (!enabled) {
            throw LoyaltyException.notFound("Partner registration is not enabled on this deployment");
        }

        String phone;
        Instant assertedAt = null;
        String jti = null;
        PhoneRegistration.Source source;

        if ("veengu".equals(authMode)) {
            // The customer's own session is the proof. The phone comes ONLY
            // from Veengu's answer — a body phoneNumber is ignored here for the
            // same reason assertion mode ignores it: pairing a valid credential
            // with someone ELSE'S number must be impossible by construction.
            if (!veenguClient.isConfigured()) {
                metrics.incPartnerRegistrationRejected("unconfigured");
                throw unconfigured();
            }
            switch (veenguClient.identify(veenguToken)) {
                case com.innbucks.loyaltyservice.client.VeenguIdentityClient.Verified v -> {
                    phone = v.phoneNumber();
                    source = PhoneRegistration.Source.VEENGU_SESSION;
                }
                case com.innbucks.loyaltyservice.client.VeenguIdentityClient.Rejected r -> {
                    // Logged with the reason, answered without it — same opaque
                    // 401 as the other modes.
                    log.warn("Veengu session registration rejected: {}", r.reason());
                    metrics.incPartnerRegistrationRejected("veengu_rejected");
                    throw unauthorized();
                }
                case com.innbucks.loyaltyservice.client.VeenguIdentityClient.Unavailable u -> {
                    // FAIL CLOSED, but retryably: no answer from Veengu is not
                    // a verdict on the token, so it must not be the opaque 401
                    // (the FE would tell the customer they were refused) and it
                    // must NEVER register anything.
                    log.warn("Veengu session registration upstream unavailable: {}", u.reason());
                    metrics.incPartnerRegistrationRejected("veengu_unavailable");
                    throw LoyaltyException.serviceUnavailable("REGISTRATION_UPSTREAM_UNAVAILABLE",
                            "Registration could not be verified right now. Please try again.");
                }
            }
        } else if ("innbucks".equals(authMode)) {
            // DEAD BRANCH — unreachable unless someone sets auth-mode=innbucks,
            // which they must not. It reads a CLAIMED phone from the body and
            // "proves" it by asking whether the caller's token can reach that
            // number's account. That reasoning assumed the platform refuses a
            // token addressing a number it does not own; measurement showed it
            // answers for any customer, so the claim is believed on the strength
            // of a directory lookup. Retained only because V42 is applied
            // history — see CLAUDE.md and InnbucksSessionClient.
            if (!innbucksClient.isConfigured()) {
                metrics.incPartnerRegistrationRejected("unconfigured");
                throw unconfigured();
            }
            if (body == null || body.phoneNumber() == null || body.phoneNumber().isBlank()) {
                metrics.incPartnerRegistrationRejected("bad_phone");
                throw LoyaltyException.badRequest("BAD_PHONE", "Please provide a phone number.");
            }
            // Normalise BEFORE the probe so the number we prove is character-
            // identical to the number we register — proving one spelling and
            // storing another would register something unproven.
            String claimed = userService.normalizePhone(body.phoneNumber());
            switch (innbucksClient.verifyOwnership(innbucksUserToken, claimed)) {
                case com.innbucks.loyaltyservice.client.InnbucksSessionClient.Verified ignored -> {
                    phone = claimed;
                    source = PhoneRegistration.Source.INNBUCKS_SESSION;
                }
                case com.innbucks.loyaltyservice.client.InnbucksSessionClient.Rejected r -> {
                    // Logged with the reason, answered without it — the caller
                    // must not learn whether the number exists, whether their
                    // token is dead, or which check failed.
                    log.warn("InnBucks session registration rejected phone={} reason={}",
                            MsisdnMasking.mask(claimed), r.reason());
                    metrics.incPartnerRegistrationRejected("innbucks_rejected");
                    throw unauthorized();
                }
                case com.innbucks.loyaltyservice.client.InnbucksSessionClient.Unavailable u -> {
                    // FAIL CLOSED but retryably: no answer from the middleware is
                    // not a verdict on the claim, so it must not be the opaque
                    // 401 (the FE would tell the customer they were refused) and
                    // must NEVER register.
                    log.warn("InnBucks session registration upstream unavailable phone={} reason={}",
                            MsisdnMasking.mask(claimed), u.reason());
                    metrics.incPartnerRegistrationRejected("innbucks_unavailable");
                    throw LoyaltyException.serviceUnavailable("REGISTRATION_UPSTREAM_UNAVAILABLE",
                            "Registration could not be verified right now. Please try again.");
                }
            }
        } else if ("innbucks_validate".equals(authMode)) {
            // ELIGIBILITY mode (V44). The platform owner's decision: every
            // InnBucks customer may spend loyalty points, so "this msisdn is a
            // real InnBucks customer" — answered by the app-authorized
            // /validate endpoint — is the whole question. Deliberately NOT an
            // ownership proof: anyone can name any customer's number here and
            // cause its (already eligible) owner to become spendable. What
            // keeps that bounded is that this mode NEVER returns a session
            // (selfServiceMode() excludes it), so the caller gains no ability
            // to act as the phone they named.
            if (!validateClient.isConfigured()) {
                metrics.incPartnerRegistrationRejected("unconfigured");
                throw unconfigured();
            }
            if (body == null || body.phoneNumber() == null || body.phoneNumber().isBlank()) {
                metrics.incPartnerRegistrationRejected("bad_phone");
                throw LoyaltyException.badRequest("BAD_PHONE", "Please provide a phone number.");
            }
            // Normalise BEFORE the check so the number we validate is character-
            // identical to the number we register.
            String claimed = userService.normalizePhone(body.phoneNumber());
            switch (validateClient.checkCustomer(claimed)) {
                case com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient.Customer ignored -> {
                    phone = claimed;
                    source = PhoneRegistration.Source.INNBUCKS_VALIDATE;
                }
                case com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient.NotACustomer r -> {
                    // Logged with the reason, answered without it — same opaque
                    // 401 as every other refusal on this endpoint, so it is not
                    // a free is-this-an-InnBucks-customer oracle.
                    log.warn("InnBucks validate registration rejected phone={} reason={}",
                            MsisdnMasking.mask(claimed), r.reason());
                    metrics.incPartnerRegistrationRejected("innbucks_validate_not_customer");
                    throw unauthorized();
                }
                case com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient.Unavailable u -> {
                    // FAIL CLOSED but retryably: no answer (or our own platform
                    // credentials refused) is not a verdict on the msisdn, so it
                    // must not be the opaque 401 and must NEVER register.
                    log.warn("InnBucks validate registration upstream unavailable phone={} reason={}",
                            MsisdnMasking.mask(claimed), u.reason());
                    metrics.incPartnerRegistrationRejected("innbucks_validate_unavailable");
                    throw LoyaltyException.serviceUnavailable("REGISTRATION_UPSTREAM_UNAVAILABLE",
                            "Registration could not be verified right now. Please try again.");
                }
            }
        } else if ("key".equals(authMode)) {
            if (partnerKey == null || partnerKey.isBlank()) {
                metrics.incPartnerRegistrationRejected("unconfigured");
                throw unconfigured();
            }
            if (!keyMatches(presentedKey)) {
                metrics.incPartnerRegistrationRejected("bad_key");
                throw unauthorized();
            }
            if (body == null || body.phoneNumber() == null || body.phoneNumber().isBlank()) {
                metrics.incPartnerRegistrationRejected("bad_phone");
                throw LoyaltyException.badRequest("BAD_PHONE", "Please provide a phone number.");
            }
            phone = body.phoneNumber();
            source = PhoneRegistration.Source.PARTNER_KEY;
        } else {
            if (!verifier.isConfigured()) {
                metrics.incPartnerRegistrationRejected("unconfigured");
                throw unconfigured();
            }
            RegistrationAssertionVerifier.VerifiedRegistration verified;
            try {
                verified = verifier.verify(body == null ? null : body.assertion());
            } catch (RegistrationAssertionVerifier.AssertionInvalidException e) {
                // Logged with the reason, answered without it.
                log.warn("Registration assertion rejected: {}", e.getMessage());
                metrics.incPartnerRegistrationRejected("bad_assertion");
                throw unauthorized();
            }
            phone = verified.phoneNumber();
            assertedAt = verified.assertedAt();
            jti = verified.jti();
            source = PhoneRegistration.Source.PARTNER_ASSERTION;
        }

        UserService.RegistrationResult result =
                userService.registerPhone(phone, source, body == null ? null : body.externalUserId(), assertedAt, jti);

        // Best-effort, and only when something actually flipped — this endpoint
        // is expected to be called on every login, and a customer must not be
        // texted "your points are active" each time they open the app.
        if (result.projectionsPromoted() > 0) {
            memberNotifier.notifyPointsUnlocked(phone);
        }
        log.info("Partner registration source={} phone={} newly={} promoted={} replay={}",
                source, MsisdnMasking.mask(phone), result.newlyRegistered(),
                result.projectionsPromoted(), result.replay());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phoneNumber", phone);
        data.put("registered", true);
        data.put("newlyRegistered", result.newlyRegistered());
        data.put("projectionsPromoted", result.projectionsPromoted());
        data.put("replay", result.replay());

        // A session, but ONLY where the caller is the customer's own device.
        //
        // In `innbucks` and `veengu` the proof IS the customer's own live
        // session with their bank, so handing back a loyalty session simply
        // continues what they already established — and without it a customer
        // who just proved their phone would still have to receive an SMS before
        // they could spend, which is the second authentication this whole mode
        // exists to remove.
        //
        // In `assertion` and `key` the caller is a partner's BACKEND proving a
        // phone on someone's behalf. Giving that backend a live customer
        // session would let it act AS every customer it registers — a different
        // and much larger power than the one it was granted. Registering for
        // someone is legitimate; becoming them is not. So the token is withheld,
        // and those callers get exactly the response they got before.
        if (selfServiceMode()) {
            data.put("loyaltyToken", sessionIssuer.issue(phone));
            data.put("expiresInSeconds", sessionIssuer.ttlSeconds());
        }
        return ResponseEntity.ok(ApiResult.ok("Phone registration recorded", data));
    }

    /**
     * Request body. In {@code assertion} mode only {@code assertion} is read —
     * {@code phoneNumber} is ignored there ON PURPOSE, so a caller cannot pair a
     * valid assertion for their own number with someone else's in the body.
     */
    public record PartnerRegistrationRequest(
            @Schema(description = "Signed registration assertion (compact JWS). Required in `assertion` mode.")
            String assertion,
            @Schema(description = "E.164 phone. Read in `key` mode, in `innbucks_validate` mode as the msisdn checked against the InnBucks customer directory, and in the dead `innbucks` mode as the CLAIM to be proved against the caller's user token. Ignored in `assertion` mode (phone comes from the signed `sub`) and in `veengu` mode (phone comes from Veengu's answer).",
                    example = "+263771234567")
            String phoneNumber,
            @Schema(description = "Opaque identifier for the account at the partner, stored for traceability.",
                    example = "veengu-9f2c1b7e")
            String externalUserId) {}

    private boolean keyMatches(String presented) {
        if (presented == null) {
            return false;
        }
        // Constant-time compare — String.equals exits at the first differing
        // byte and leaks the key one byte at a time to a patient caller.
        return MessageDigest.isEqual(
                partnerKey.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * True in the modes whose caller is the CUSTOMER'S OWN DEVICE, proving a
     * phone with a session they already hold — the only callers that should be
     * handed a loyalty session in return.
     *
     * <p>An allow-list, not a deny-list of today's partner modes: a mode added
     * later gets no session until someone decides it should, which is the safe
     * direction to fail. Withholding a token only costs that caller an extra
     * step; issuing one to a partner backend hands it every customer it touches.
     *
     * <p>{@code innbucks_validate} is deliberately NOT here and never may be:
     * its check proves a msisdn belongs to SOME InnBucks customer, not that the
     * caller holds it, so a session for it would be a passwordless login to any
     * customer account by naming their number. The no-session property is the
     * entire boundary that makes the eligibility decision safe to implement —
     * {@code PartnerRegistrationSessionScopingTest} pins it.
     */
    private boolean selfServiceMode() {
        return "innbucks".equals(authMode) || "veengu".equals(authMode);
    }

    private static LoyaltyException unauthorized() {
        return LoyaltyException.unauthorized("REGISTRATION_UNAUTHORIZED",
                "Registration proof was not accepted.");
    }

    private static LoyaltyException unconfigured() {
        return LoyaltyException.serviceUnavailable("REGISTRATION_UNCONFIGURED",
                "Partner registration is enabled but no credential is configured.");
    }
}
