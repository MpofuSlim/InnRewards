package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <b>UNSOUND. This client backs a registration mode that must never be enabled
 * — see the CAUTION in {@code CLAUDE.md} before touching anything here.</b>
 *
 * <p>It was built to prove that the holder of an InnBucks Client Service
 * <b>user token</b> owns a CLAIMED phone number, by asking the middleware to
 * read that number's account under that token.
 *
 * <h2>Why this shape, and why it does not work</h2>
 * The Client Service API exposes no identity endpoint — nothing takes a token
 * and returns its holder (confirmed by inventorying all 88 request definitions
 * across the partner's own Postman collections). So the question was asked
 * backwards: the caller names a phone, and we check whether their token can
 * reach it.
 *
 * <p>That is a proof only if the platform REFUSES when the token does not own
 * the number. It does not. Measured against {@code staging.innbucks.co.zw} with
 * an app/merchant bearer — a credential proving nothing about any customer —
 * the probe returned another customer's name and account numbers, and its
 * sibling balance endpoint returned that customer's balance. The probe is a
 * <b>directory lookup</b>, not a token-bound read; its twin is step one of the
 * collection's own P2P recipient lookup, which is cross-customer by design.
 *
 * <p>Enabling this would therefore let anyone holding any customer token
 * register any other customer's phone — and, since {@code innbucks} is a
 * self-service mode, receive a live loyalty session for it. Every candidate
 * replacement probe path was inventoried and rejected; {@code CLAUDE.md} records
 * the two conditions under which such a proof could ever become viable.
 *
 * <h2>Still true: the path is NOT /validate</h2>
 * <ul>
 *   <li>{@code GET /auth/client-service/msisdn/{msisdn}/validate} is authorized
 *       by the APP's own client-service token and answers "00" for every real
 *       InnBucks customer. It proves a number EXISTS.</li>
 *   <li>The msisdn-scoped account endpoint used here is at least addressed with
 *       the CUSTOMER's user token — which is why it looked like a proof.</li>
 * </ul>
 * Neither can register anything. No value of {@code probe-path} makes this mode
 * safe, so a clean boot log is not a green light.
 *
 * <p>The notes below describe engineering that remains correct in itself — the
 * responseCode handling and the Rejected/Unavailable split. They describe a
 * client that must not be switched on.
 *
 * <h2>A 2xx is not automatically a yes</h2>
 * The platform answers business failures with HTTP 200 and a non-success
 * {@code responseCode} (its own convention: {@code "00"} / {@code "000"} /
 * {@code 0} mean success, everything else is a failure with the reason in
 * {@code responseMessage} / {@code responseDescription}). Treating any 2xx as
 * proof would accept exactly the refusal we are testing for, so the code is
 * checked as well as the status.
 *
 * <h2>Three outcomes, and the middle one is not a verdict</h2>
 * <ul>
 *   <li>{@link Verified} — 2xx AND a success code. The only state that registers.</li>
 *   <li>{@link Rejected} — the middleware answered and said no: 401/403/404, or
 *       a 2xx carrying a failure code. Maps to the endpoint's opaque 401.</li>
 *   <li>{@link Unavailable} — we got no usable answer: connect/read failure,
 *       5xx, an unexpected status, or a 2xx whose body is not a JSON object.
 *       Maps to a retryable 503. Fail closed: no answer registers nothing.</li>
 * </ul>
 * The non-JSON-2xx rule is the EcoCash edge lesson — WAFs serve block pages
 * with HTTP 200 and {@code text/html}; classifying that as a refusal would tell
 * every customer they were rejected for as long as the block lasted.
 *
 * <p>The user token is a live credential for another system: never logged,
 * never stored, held only for the duration of the call.
 */
@Component
@Slf4j
public class InnbucksSessionClient {

    /** Outcome of an ownership probe. */
    public sealed interface OwnershipOutcome {}
    /** The middleware let this token read this msisdn — ownership proved. */
    public record Verified(String responseCode) implements OwnershipOutcome {}
    /** The middleware answered and refused. Not the caller's number, or a dead token. */
    public record Rejected(String reason) implements OwnershipOutcome {}
    /** No usable answer — infrastructure, never a verdict on the claim. */
    public record Unavailable(String reason) implements OwnershipOutcome {}

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String probePath;
    private final Set<String> successCodes;

    public InnbucksSessionClient(
            @Value("${loyalty.registration.partner.innbucks.base-url:}") String baseUrl,
            @Value("${loyalty.registration.partner.innbucks.api-key:}") String apiKey,
            @Value("${loyalty.registration.partner.innbucks.probe-path:/api/v1/account/msisdn/{msisdn}/details?currency=USD}") String probePath,
            @Value("${loyalty.registration.partner.innbucks.success-codes:00,000,0}") String successCodes,
            @Value("${loyalty.registration.partner.innbucks.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${loyalty.registration.partner.innbucks.read-timeout-ms:6000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.probePath = probePath == null ? "" : probePath.trim();
        this.successCodes = new LinkedHashSet<>(Arrays.stream(
                        (successCodes == null ? "" : successCodes).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        // Built unconditionally so a blank config can never fail a boot; a blank
        // base URL is never called because verifyOwnership() guards first.
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl.isBlank() ? "http://innbucks-unconfigured.invalid" : this.baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** Base URL, API key and probe path must all be provisioned. */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !probePath.isBlank();
    }

    /**
     * Asks the middleware to read {@code e164Phone}'s account under
     * {@code userToken}. Never throws — every failure mode is a typed outcome.
     *
     * @param e164Phone the CLAIMED phone, E.164 with the leading '+' as loyalty
     *                  stores it; converted to the middleware's bare format here.
     */
    public OwnershipOutcome verifyOwnership(String userToken, String e164Phone) {
        if (userToken == null || userToken.isBlank()) {
            // A caller mistake, not worth a network round-trip. Rejected rather
            // than Unavailable: there is nothing here to retry.
            return new Rejected("blank_token");
        }
        if (e164Phone == null || e164Phone.isBlank()) {
            return new Rejected("blank_phone");
        }
        if (!isConfigured()) {
            return new Unavailable("unconfigured");
        }

        // The platform's msisdn format is bare digits (263772123123), never the
        // stored E.164 '+' form. One conversion point, here.
        String msisdn = e164Phone.startsWith("+") ? e164Phone.substring(1) : e164Phone;
        String uri = probePath.replace("{msisdn}", msisdn);

        ResponseEntity<String> response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .header("X-Api-Key", apiKey)
                    .header("Authorization", "Bearer " + userToken)
                    .retrieve()
                    // Disarm throw-on-4xx/5xx: the status mapping below is the
                    // entire point of this client.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
        } catch (Exception e) {
            log.warn("InnBucks ownership probe unreachable phone={} cause={}",
                    MsisdnMasking.mask(e164Phone), e.toString());
            return new Unavailable("io_error");
        }

        int status = response.getStatusCode().value();
        if (status == 401 || status == 403 || status == 404) {
            // The middleware refused this token for this msisdn. That is the
            // refusal this whole mode is built to detect.
            return new Rejected("http_" + status);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            // 5xx, or a 4xx we did not expect (a malformed request is OUR bug).
            // Neither says anything about the claim.
            log.warn("InnBucks ownership probe unexpected status={}", status);
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
            // an answer — infrastructure, or every customer is refused while the
            // block lasts.
            log.warn("InnBucks ownership probe answered 2xx with a non-JSON body — treating as unavailable");
            return new Unavailable("malformed_2xx");
        }

        // responseCode is a string on some endpoints ("000") and a number on
        // others (0) — asText() normalises both.
        String code = node.path("responseCode").asText(null);
        if (code == null || code.isBlank()) {
            log.warn("InnBucks ownership probe 2xx carried no responseCode — treating as unavailable");
            return new Unavailable("no_response_code");
        }
        if (!successCodes.contains(code.trim())) {
            // A 2xx with a failure code is a CLEAR refusal — the middleware
            // processed the request and declined. Never read a bare 2xx as
            // proof, or the refusal we are testing for becomes a pass.
            return new Rejected("code_" + code.trim());
        }
        return new Verified(code.trim());
    }
}
