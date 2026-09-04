package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asks Veengu whose session a customer access token belongs to, via the
 * Frontend API's {@code GET /auth/identity} (pinned in ticketing-system's
 * {@code docs/api/veengu-openapi.json}, Veengu Platform Frontend API v3.1.0).
 *
 * <p>This is what makes the {@code veengu} registration mode safe to expose to
 * a mobile client: the FE forwards its own session token, and the phone number
 * comes from <b>Veengu's answer</b> — the endpoint declares exactly two headers,
 * {@code v-tenant} (the tenant code) and {@code v-access-token} (the customer's
 * session), so there is no partner credential here at all and nothing for a
 * forged request to prove.
 *
 * <p><b>Veengu is an EXTERNAL service</b> — plain {@code RestClient} + explicit
 * URL, not the {@code @LoadBalanced} builder, per the discovery conventions
 * (same as the notification providers).
 *
 * <h2>Outcome model — three states, and the middle one matters</h2>
 * <ul>
 *   <li>{@link Verified} — Veengu answered 200 with a JSON object naming a
 *       phone. The only state that ever registers anything.</li>
 *   <li>{@link Rejected} — Veengu POSITIVELY refused: 401/403, the documented
 *       404 ("Identity not found"), or an identity with no phone on it. Maps to
 *       the endpoint's opaque 401.</li>
 *   <li>{@link Unavailable} — we could not get an answer: connect/read failure,
 *       5xx, an unexpected 4xx (our request was malformed — that is our bug,
 *       not the customer's token), or a 2xx whose body is not a JSON object.
 *       Maps to a 503 so the FE retries instead of treating the customer as
 *       refused. Fail closed, never open: no answer registers nothing.</li>
 * </ul>
 *
 * <p>The non-JSON-2xx rule is the EcoCash edge lesson: WAFs and bot-mitigation
 * layers serve their block pages with HTTP 200 and text/html. Classifying that
 * as a rejection would refuse every customer for as long as the block lasts.
 *
 * <p>The access token is a live credential for ANOTHER system: it is never
 * logged, never stored, and held only for the duration of the call.
 */
@Component
@Slf4j
public class VeenguIdentityClient {

    /** Outcome of an identity check. Same-file sealed hierarchy. */
    public sealed interface IdentityOutcome {}
    /** Veengu confirmed the session and named this phone (E.164 per the spec). */
    public record Verified(String phoneNumber) implements IdentityOutcome {}
    /** Veengu positively refused the token, or the identity carries no phone. */
    public record Rejected(String reason) implements IdentityOutcome {}
    /** No answer obtained — infrastructure, never a verdict on the token. */
    public record Unavailable(String reason) implements IdentityOutcome {}

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String tenant;

    public VeenguIdentityClient(
            @Value("${loyalty.registration.partner.veengu.base-url:}") String baseUrl,
            @Value("${loyalty.registration.partner.veengu.tenant:}") String tenant,
            @Value("${loyalty.registration.partner.veengu.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${loyalty.registration.partner.veengu.read-timeout-ms:6000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.tenant = tenant == null ? "" : tenant.trim();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        // Built unconditionally (a blank baseUrl never gets called — identify()
        // guards on isConfigured() first) so construction can't fail a boot.
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl.isBlank() ? "http://veengu-unconfigured.invalid" : this.baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** Both the instance base URL and the tenant code must be provisioned. */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !tenant.isBlank();
    }

    /**
     * Validates a customer session token against Veengu and returns whose it is.
     * Never throws — every failure mode is a typed outcome.
     */
    public IdentityOutcome identify(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            // Guard rail: a blank token is a caller mistake, not a reason to
            // spend a network round-trip. Rejected, not Unavailable — there is
            // nothing to retry.
            return new Rejected("blank_token");
        }
        if (!isConfigured()) {
            return new Unavailable("unconfigured");
        }
        ResponseEntity<String> response;
        try {
            response = restClient.get()
                    .uri("/auth/identity")
                    .header("v-tenant", tenant)
                    .header("v-access-token", accessToken)
                    .retrieve()
                    // Disarm the default throw-on-4xx/5xx: status mapping below
                    // is the whole point of this client.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
        } catch (Exception e) {
            // Connect refused, DNS, read timeout — infrastructure. Never the
            // token's fault, never a rejection.
            log.warn("Veengu identity check unreachable cause={}", e.toString());
            return new Unavailable("io_error");
        }

        int status = response.getStatusCode().value();
        if (status == 401 || status == 403 || status == 404) {
            // 404 is the DOCUMENTED "Identity not found"; 401/403 are the edge
            // refusing the token. All positive refusals of this credential.
            return new Rejected("http_" + status);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            // A 400 here means WE built a bad request; 5xx means Veengu is
            // down. Neither says anything about the customer's token.
            log.warn("Veengu identity check unexpected status={}", status);
            return new Unavailable("http_" + status);
        }

        String body = response.getBody();
        JsonNode node;
        try {
            node = body == null ? null : objectMapper.readTree(body);
        } catch (Exception e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            // A 2xx that is not a JSON object is a WAF/bot-mitigation page, not
            // an identity — infrastructure, or every customer is refused for as
            // long as the block lasts.
            log.warn("Veengu identity check answered 2xx with a non-JSON body — treating as unavailable");
            return new Unavailable("malformed_2xx");
        }
        String phone = node.path("phoneNumber").asText(null);
        if (phone == null || phone.isBlank()) {
            // A real identity with no phone number on it cannot register a
            // phone. Positive answer, unusable for this purpose.
            return new Rejected("no_phone");
        }
        return new Verified(phone.trim());
    }
}
