package com.innbucks.loyaltyservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.config.InnbucksNotifyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Sends plain-text email through the InnBucks public notification API
 * ({@code POST /api/notification/email}) — the SAME authenticated gateway the
 * platform's SMS + payment rails use: an {@code X-Api-Key} header plus a bearer
 * from {@code POST /auth/third-party} (cached until the JWT exp, refreshed once
 * on a 401). Wire body: {@code {subject, message, reference, destinationEmail}}.
 *
 * <p>Mirrors {@link SmsNotificationClient}'s auth handling. Used by
 * {@link InvoiceEmailNotifier} to deliver merchant invoices. Failures surface as
 * {@link NotificationDeliveryException} so callers keep best-effort semantics.
 * Never logs the message body (may carry billing detail).
 */
@Slf4j
@Component
public class EmailNotificationClient {

    private static final String LOGIN_PATH = "/auth/third-party";
    private static final String EMAIL_PATH = "/api/notification/email";
    private static final String API_KEY_HEADER = "X-Api-Key";

    private final RestClient restClient;
    private final InnbucksNotifyProperties properties;
    private final ObjectMapper objectMapper;

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public EmailNotificationClient(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                   InnbucksNotifyProperties properties,
                                   ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void sendEmail(String to, String subject, String message, String reference) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Email recipient is blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new NotificationDeliveryException("Email subject is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("Email message is blank");
        }
        requireConfigured();
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "TKT-EMAIL-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject", subject);
        payload.put("message", message);
        payload.put("reference", ref);
        payload.put("destinationEmail", to);

        withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(EMAIL_PATH)
                        .header(API_KEY_HEADER, properties.getApiKey())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401) {
                    throw new UnauthorizedException();
                }
                log.warn("Notification API rejected email ref={} status={} body={}",
                        ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected email: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for email ref={} error={}", ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
        log.info("Email notification accepted by notification API ref={}", ref);
    }

    /** Run an authed call; on 401, force one token refresh and replay once. */
    private <T> T withAuthRetryOn401(Function<String, T> call) {
        try {
            return call.apply(currentToken(false));
        } catch (UnauthorizedException first) {
            log.info("Notification API returned 401 for email — refreshing token and replaying once");
            try {
                return call.apply(currentToken(true));
            } catch (UnauthorizedException second) {
                throw new NotificationDeliveryException(
                        "Notification API rejected our credentials twice (401) — check BANK_API_USERNAME/PASSWORD/KEY");
            }
        }
    }

    private synchronized String currentToken(boolean force) {
        if (!force && accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        try {
            String raw = restClient.post()
                    .uri(LOGIN_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", properties.getUsername(),
                            "password", properties.getPassword()))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = parseJson(raw);
            Object token = parsed.get("accessToken");
            if (token == null || token.toString().isBlank()) {
                throw new NotificationDeliveryException("Notification API login returned no accessToken");
            }
            accessToken = token.toString();
            tokenExpiry = deriveExpiry(accessToken).minusSeconds(30);
            return accessToken;
        } catch (RestClientResponseException e) {
            log.warn("Notification API rejected login status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "Notification API login failed: HTTP " + e.getStatusCode().value(), e);
        } catch (NotificationDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NotificationDeliveryException(
                    "Unable to reach the notification API for login: " + e.getMessage(), e);
        }
    }

    /** Best-effort JWT exp parse; falls back to the configured token TTL. */
    private Instant deriveExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                Object exp = parseJson(payloadJson).get("exp");
                if (exp instanceof Number n) {
                    return Instant.ofEpochSecond(n.longValue());
                }
            }
        } catch (RuntimeException ignored) {
            // Opaque token — fall through to TTL.
        }
        return Instant.now().plus(properties.getTokenTtl());
    }

    private Map<String, Object> parseJson(String raw) {
        try {
            return objectMapper.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new NotificationDeliveryException("Notification API returned an unparseable response", e);
        }
    }

    private void requireConfigured() {
        if (isBlank(properties.getBaseUrl()) || isBlank(properties.getApiKey())
                || isBlank(properties.getUsername()) || isBlank(properties.getPassword())) {
            throw new NotificationDeliveryException(
                    "Notification API is not configured — set BANK_API_URL/BANK_API_KEY/BANK_API_USERNAME/BANK_API_PASSWORD");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Internal marker for a 401 so {@link #withAuthRetryOn401} can replay once. */
    private static final class UnauthorizedException extends RuntimeException {
    }
}
