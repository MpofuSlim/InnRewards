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
 * <h2>Three auth modes, and why the default is the signed one</h2>
 * <ul>
 *   <li>{@code assertion} (default) — the caller sends a short-lived,
 *       phone-scoped token signed with a private key it alone holds. Loyalty
 *       stores only the public key, so nothing here is worth stealing, and a
 *       captured assertion names one phone that was already registered.</li>
 *   <li>{@code key} — a shared secret in {@code X-Partner-Key}, for a caller
 *       that cannot sign. Honest about its weakness: whoever holds the key can
 *       register ANY phone, so it is opt-in, never the default, and the key must
 *       never reach a mobile client.</li>
 *   <li>{@code veengu} — the customer's OWN session is the proof. The FE
 *       forwards its Veengu access token in {@code X-Veengu-Access-Token};
 *       loyalty validates it against Veengu's {@code GET /auth/identity} and
 *       registers the phone <b>Veengu's answer</b> names. The one mode a mobile
 *       client may call directly: there is no credential in it to steal, and a
 *       stolen session token can only register the phone of the account it was
 *       stolen from — no escalation beyond the theft itself. Fail-closed: if
 *       Veengu cannot be reached, nothing is registered and the caller gets a
 *       retryable 503, never a default-to-registered.</li>
 * </ul>
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
    private final MemberActivityNotifier memberNotifier;
    private final LoyaltyMetrics metrics;
    private final boolean enabled;
    private final String authMode;
    private final String partnerKey;

    public PartnerRegistrationController(
            UserService userService,
            RegistrationAssertionVerifier verifier,
            com.innbucks.loyaltyservice.client.VeenguIdentityClient veenguClient,
            MemberActivityNotifier memberNotifier,
            LoyaltyMetrics metrics,
            @Value("${loyalty.registration.partner.enabled:false}") boolean enabled,
            @Value("${loyalty.registration.partner.auth-mode:assertion}") String authMode,
            @Value("${loyalty.registration.partner.key:}") String partnerKey) {
        this.userService = userService;
        this.verifier = verifier;
        this.veenguClient = veenguClient;
        this.memberNotifier = memberNotifier;
        this.metrics = metrics;
        this.enabled = enabled;
        this.authMode = authMode == null ? "assertion" : authMode.trim().toLowerCase();
        this.partnerKey = partnerKey;
    }

    @PostMapping("/registrations")
    @Operation(summary = "Record that a phone's owner has proven they hold it",
            description = """
                    Records the proof that activates every loyalty projection of a phone, now and in \
                    future. Who calls it depends on the cell's auth mode: a trusted partner backend \
                    (`assertion` / `key` modes), or the customer app itself (`veengu` mode — the FE \
                    forwards its own Veengu access token in `X-Veengu-Access-Token`, loyalty validates \
                    it against Veengu's `GET /auth/identity`, and the phone registered is the one \
                    VEENGU's answer names, never one the caller typed).

                    Idempotent and safe to call on every login: a repeat is a no-op that reports \
                    `projectionsPromoted: 0`. In `assertion` mode the phone is taken ONLY from the \
                    signed `sub` claim; in `veengu` mode ONLY from Veengu's answer. The body \
                    `phoneNumber` is read in `key` mode alone.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registration recorded (or already present)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "First registration", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Phone registration recorded",
                                              "data": {
                                                "phoneNumber": "+263771234567",
                                                "registered": true,
                                                "newlyRegistered": true,
                                                "projectionsPromoted": 2,
                                                "replay": false
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
                    + "or REGISTRATION_UPSTREAM_UNAVAILABLE — `veengu` mode could not reach Veengu, retry",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Half-provisioned", value = """
                                            {
                                              "code": "REGISTRATION_UNCONFIGURED",
                                              "message": "Partner registration is enabled but no credential is configured.",
                                              "data": null
                                            }"""),
                                    @ExampleObject(name = "Veengu unreachable (retryable)", value = """
                                            {
                                              "code": "REGISTRATION_UPSTREAM_UNAVAILABLE",
                                              "message": "Registration could not be verified right now. Please try again.",
                                              "data": null
                                            }""")}))
    })
    public ResponseEntity<ApiResult<Map<String, Object>>> register(
            @RequestHeader(value = "X-Partner-Key", required = false) String presentedKey,
            @RequestHeader(value = "X-Veengu-Access-Token", required = false) String veenguToken,
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
            @Schema(description = "E.164 phone. Read ONLY in `key` mode; ignored in `assertion` mode (phone comes from the signed `sub`) and in `veengu` mode (phone comes from Veengu's answer).",
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

    private static LoyaltyException unauthorized() {
        return LoyaltyException.unauthorized("REGISTRATION_UNAUTHORIZED",
                "Registration proof was not accepted.");
    }

    private static LoyaltyException unconfigured() {
        return LoyaltyException.serviceUnavailable("REGISTRATION_UNCONFIGURED",
                "Partner registration is enabled but no credential is configured.");
    }
}
