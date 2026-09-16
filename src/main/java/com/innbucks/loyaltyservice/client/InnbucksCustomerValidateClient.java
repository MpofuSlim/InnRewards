package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Asks the InnBucks Client Service API whether a phone number belongs to a real
 * InnBucks customer ({@code GET /auth/client-service/msisdn/{msisdn}/validate}),
 * authorized by the PLATFORM'S OWN credentials.
 *
 * <h2>What this proves — and what it deliberately does not</h2>
 * This is an <b>ELIGIBILITY</b> check, not an ownership proof. The endpoint is
 * authorized by the app's credentials and answers {@code "00"} for every real
 * InnBucks customer, whoever asks — the very property that made it unusable as
 * the probe for the dead {@code innbucks} session mode (see
 * {@link InnbucksSessionClient}). It is used here under a different, explicit
 * platform-owner decision (2026-09): <i>every InnBucks customer is eligible to
 * spend loyalty points</i>, so "this msisdn is a customer" is exactly the
 * question, and "does the caller hold it" is deliberately not asked at this
 * layer. Consequently a registration produced from this client
 * ({@code PhoneRegistration.Source.INNBUCKS_VALIDATE}) MUST never mint a
 * loyalty session — identity still belongs to the OTP / assertion channels.
 *
 * <h2>Auth</h2>
 * Same platform gateway pattern as {@code SmsNotificationClient}: an
 * {@code X-Api-Key} header plus a bearer minted at {@code POST /auth/third-party}
 * from the fleet's {@code BANK_API_USERNAME}/{@code BANK_API_PASSWORD}, cached
 * until the JWT's own {@code exp} (TTL fallback for an opaque token) and
 * refreshed once on a 401/403 before giving up. Config defaults chain to the
 * {@code BANK_API_*} env vars so a cell that already sends SMS needs no new
 * secret. The login path is configurable in case the client-service surface
 * ever authenticates differently from the notification surface — measure on
 * staging before assuming.
 *
 * <h2>Three outcomes, and the middle one is not a verdict</h2>
 * <ul>
 *   <li>{@link Customer} — 2xx JSON with a success {@code responseCode}. The
 *       only outcome that may register anything.</li>
 *   <li>{@link NotACustomer} — the platform processed the request and said no:
 *       a 2xx with a failure code (its documented business-refusal shape), or a
 *       404 on the msisdn. The 404 mapping is an ASSUMPTION (unmeasured for
 *       this endpoint): a path misconfiguration would surface as every check
 *       failing {@code http_404}, which is why the reason string is distinct —
 *       watch {@code loyalty.registration.backlog.checked{outcome="not_customer"}}
 *       and the logs after any config change.</li>
 *   <li>{@link Unavailable} — no usable answer: connect/read failure, 5xx, an
 *       unexpected 4xx, a login failure, our own credentials refused, or a 2xx
 *       whose body is not a JSON object (the WAF-block-page lesson: bot
 *       mitigation serves text/html with HTTP 200). Never a verdict; callers
 *       retry later and register nothing.</li>
 * </ul>
 * A refusal of OUR credentials (401/403 that survives one token refresh) is
 * {@link Unavailable}, never {@link NotACustomer}: expired platform creds must
 * read as an outage, not as "none of your customers exist".
 */
@Component
@Slf4j
public class InnbucksCustomerValidateClient {

    /** Outcome of a customer-existence check. */
    public sealed interface CustomerCheckOutcome {}
    /** The platform confirmed this msisdn belongs to a real InnBucks customer. */
    public record Customer(String responseCode) implements CustomerCheckOutcome {}
    /** The platform answered and said this msisdn is not a customer. */
    public record NotACustomer(String reason) implements CustomerCheckOutcome {}
    /** No usable answer — infrastructure, never a verdict on the msisdn. */
    public record Unavailable(String reason) implements CustomerCheckOutcome {}

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String username;
    private final String password;
    private final String loginPath;
    private final String validatePath;
    private final Set<String> successCodes;
    private final Duration tokenTtl;

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public InnbucksCustomerValidateClient(
            @Value("${loyalty.registration.innbucks-validate.base-url:}") String baseUrl,
            @Value("${loyalty.registration.innbucks-validate.api-key:}") String apiKey,
            @Value("${loyalty.registration.innbucks-validate.username:}") String username,
            @Value("${loyalty.registration.innbucks-validate.password:}") String password,
            @Value("${loyalty.registration.innbucks-validate.login-path:/auth/third-party}") String loginPath,
            @Value("${loyalty.registration.innbucks-validate.validate-path:/auth/client-service/msisdn/{msisdn}/validate}") String validatePath,
            @Value("${loyalty.registration.innbucks-validate.success-codes:00,000,0}") String successCodes,
            @Value("${loyalty.registration.innbucks-validate.token-ttl-seconds:480}") long tokenTtlSeconds,
            @Value("${loyalty.registration.innbucks-validate.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${loyalty.registration.innbucks-validate.read-timeout-ms:6000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
        this.loginPath = loginPath == null ? "" : loginPath.trim();
        this.validatePath = validatePath == null ? "" : validatePath.trim();
        this.successCodes = new LinkedHashSet<>(Arrays.stream(
                        (successCodes == null ? "" : successCodes).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        this.tokenTtl = Duration.ofSeconds(Math.max(30, tokenTtlSeconds));
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        // Built unconditionally so a blank config can never fail a boot; a blank
        // base URL is never called because checkCustomer() guards first.
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl.isBlank() ? "http://innbucks-unconfigured.invalid" : this.baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** Base URL, API key, login credentials and both paths must be provisioned. */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !username.isBlank()
                && !password.isBlank() && !loginPath.isBlank() && !validatePath.isBlank();
    }

    /**
     * Asks the platform whether {@code e164Phone} belongs to a real InnBucks
     * customer. Never throws — every failure mode is a typed outcome.
     *
     * @param e164Phone E.164 with the leading '+' as loyalty stores it;
     *                  converted to the platform's bare msisdn format here.
     */
    public CustomerCheckOutcome checkCustomer(String e164Phone) {
        if (e164Phone == null || e164Phone.isBlank()) {
            // A caller mistake, not worth a network round-trip, and nothing to
            // retry — the controller normalizes (400 BAD_PHONE) before us.
            return new NotACustomer("blank_phone");
        }
        if (!isConfigured()) {
            return new Unavailable("unconfigured");
        }

        String token;
        try {
            token = currentToken(false);
        } catch (LoginFailedException e) {
            log.warn("InnBucks validate: platform login failed ({})", e.reason);
            return new Unavailable(e.reason);
        }

        // The platform's msisdn format is bare digits (263772123123), never the
        // stored E.164 '+' form. One conversion point, here.
        String msisdn = e164Phone.startsWith("+") ? e164Phone.substring(1) : e164Phone;
        String uri = validatePath.replace("{msisdn}", msisdn);

        ResponseEntity<String> response = callValidate(uri, token, e164Phone);
        if (response == null) {
            return new Unavailable("io_error");
        }

        int status = response.getStatusCode().value();
        if (status == 401 || status == 403) {
            // OUR bearer was refused. Force one refresh and replay once — the
            // cached token may simply have expired between the cache check and
            // the call. A second refusal is a credential/provisioning problem:
            // an outage to us, never a verdict on the msisdn.
            try {
                token = currentToken(true);
            } catch (LoginFailedException e) {
                return new Unavailable(e.reason);
            }
            response = callValidate(uri, token, e164Phone);
            if (response == null) {
                return new Unavailable("io_error");
            }
            status = response.getStatusCode().value();
            if (status == 401 || status == 403) {
                log.warn("InnBucks validate: platform refused our credentials twice (http_{}) — check "
                        + "BANK_API_USERNAME/PASSWORD/KEY (or the LOYALTY_INNBUCKS_VALIDATE_* overrides)", status);
                return new Unavailable("credentials_rejected");
            }
        }
        if (status == 404) {
            // ASSUMPTION (unmeasured): a 404 on the msisdn path is "no such
            // customer". The platform's documented refusal shape is 200 + a
            // failure responseCode, so a sustained run of http_404 across many
            // phones more likely means the validate-path is wrong — the reason
            // string stays distinct so that shows up in logs and metrics.
            return new NotACustomer("http_404");
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            // 5xx, or a 4xx we did not expect (a malformed request is OUR bug).
            // Neither says anything about the msisdn.
            log.warn("InnBucks validate: unexpected status={}", status);
            return new Unavailable("http_" + status);
        }

        JsonNode node;
        try {
            String body = response.getBody();
            node = body == null ? null : objectMapper.readTree(body);
        } catch (Exception e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            // A 2xx that is not a JSON object is a WAF/bot-mitigation page, not
            // an answer — infrastructure, or every customer would read as
            // refused while the block lasts.
            log.warn("InnBucks validate: 2xx with a non-JSON body — treating as unavailable");
            return new Unavailable("malformed_2xx");
        }

        // responseCode is a string on some endpoints ("00") and a number on
        // others (0) — asText() normalises both.
        String code = node.path("responseCode").asText(null);
        if (code == null || code.isBlank()) {
            log.warn("InnBucks validate: 2xx carried no responseCode — treating as unavailable");
            return new Unavailable("no_response_code");
        }
        if (!successCodes.contains(code.trim())) {
            // A 2xx with a failure code is the platform's documented business
            // refusal: it processed the request and this msisdn is not a
            // customer (or cannot be validated).
            return new NotACustomer("code_" + code.trim());
        }
        return new Customer(code.trim());
    }

    /** One GET to the validate path; null means an I/O-level failure. */
    private ResponseEntity<String> callValidate(String uri, String token, String e164Phone) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(API_KEY_HEADER, apiKey)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    // Disarm throw-on-4xx/5xx: the status mapping in the caller
                    // is the entire point of this client.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
        } catch (Exception e) {
            log.warn("InnBucks validate unreachable phone={} cause={}",
                    MsisdnMasking.mask(e164Phone), e.toString());
            return null;
        }
    }

    private synchronized String currentToken(boolean force) {
        if (!force && accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(loginPath)
                    .header(API_KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", username, "password", password))
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
        } catch (Exception e) {
            throw new LoginFailedException("login_io_error");
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("InnBucks validate: login refused status={}", response.getStatusCode().value());
            throw new LoginFailedException("login_rejected");
        }
        JsonNode node;
        try {
            String body = response.getBody();
            node = body == null ? null : objectMapper.readTree(body);
        } catch (Exception e) {
            node = null;
        }
        String token = node == null ? null : node.path("accessToken").asText(null);
        if (token == null || token.isBlank()) {
            throw new LoginFailedException("login_no_token");
        }
        accessToken = token;
        tokenExpiry = deriveExpiry(token).minusSeconds(30);
        log.info("InnBucks validate: platform login succeeded; token cached until {}", tokenExpiry);
        return accessToken;
    }

    /** Best-effort JWT exp parse; falls back to the configured token TTL. */
    private Instant deriveExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                JsonNode exp = objectMapper.readTree(payloadJson).path("exp");
                if (exp.isNumber()) {
                    return Instant.ofEpochSecond(exp.longValue());
                }
            }
        } catch (Exception ignored) {
            // Opaque token — fall through to TTL.
        }
        return Instant.now().plus(tokenTtl);
    }

    /** Internal marker so token-mint failures surface as {@link Unavailable}. */
    private static final class LoginFailedException extends RuntimeException {
        final String reason;
        LoginFailedException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
