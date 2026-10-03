package com.innbucks.loyaltyservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.config.InnbucksNotifyProperties;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.util.SingleFlightTokenCache;
import com.innbucks.loyaltyservice.util.SmsTextSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Sends SMS through the InnBucks public notification API
 * ({@code POST /api/notification/sms}) — the SAME authenticated gateway the
 * platform's email + payment rails use: an {@code X-Api-Key} header plus a
 * bearer from {@code POST /auth/third-party} (cached until the JWT exp,
 * refreshed once on a 401). Wire body: {@code {message, reference, destinationMsisdn}}.
 *
 * <p>Previously this posted to the unauthenticated {@code innbucks-core-gateway}
 * messenger bridge ({@code /notifications/sms}); that path was unreliable while
 * the notification API worked, so SMS now rides it. Used by
 * {@link GuestCheckoutNotifier}, {@link MemberActivityNotifier} and voucher
 * delivery. Failures surface as {@link NotificationDeliveryException} so callers
 * keep best-effort / fallback semantics. Never logs the message body (may carry
 * a voucher code) or an unmasked MSISDN.
 */
@Slf4j
@Component
public class SmsNotificationClient {

    private static final String LOGIN_PATH = "/auth/third-party";
    private static final String SMS_PATH = "/api/notification/sms";
    private static final String API_KEY_HEADER = "X-Api-Key";
    /** A token this close to its exp is refreshed (and still used while that runs). */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);
    /** Slack on top of connect + read timeout for a caller waiting on someone else's login. */
    private static final Duration LOGIN_WAIT_MARGIN = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final InnbucksNotifyProperties properties;
    private final ObjectMapper objectMapper;

    /** See {@link SingleFlightTokenCache}: no lock is ever held across the login. */
    private final SingleFlightTokenCache tokens;

    public SmsNotificationClient(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                 InnbucksNotifyProperties properties,
                                 ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tokens = new SingleFlightTokenCache(this::login, REFRESH_MARGIN,
                Duration.ofMillis((long) properties.getConnectTimeoutMs() + properties.getReadTimeoutMs())
                        .plus(LOGIN_WAIT_MARGIN),
                () -> new NotificationDeliveryException(
                        "Timed out waiting for the notification API login"));
    }

    public void sendSms(String destination, String message, String reference) {
        if (destination == null || destination.isBlank()) {
            throw new NotificationDeliveryException("SMS recipient is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("SMS message is blank");
        }
        requireConfigured();
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "TKT-SMS-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        // GSM-7 safety: the SMS gateway rejects non-GSM/non-ASCII chars (e.g. the
        // em-dash in a "— The InnBucks Team" sign-off) with 400 "Invalid message".
        // Transliterate to ASCII on the SMS path only (email keeps its typography).
        payload.put("message", SmsTextSanitizer.toGsmSafe(message));
        payload.put("reference", ref);
        payload.put("destinationMsisdn", destination);

        withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(SMS_PATH)
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
                log.warn("Notification API rejected SMS destination={} ref={} status={} body={}",
                        MsisdnMasking.mask(destination), ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected SMS: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for SMS destination={} ref={} error={}",
                        MsisdnMasking.mask(destination), ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
        log.info("SMS notification accepted by notification API destination={} ref={}",
                MsisdnMasking.mask(destination), ref);
    }

    /** Run an authed call; on 401, force one token refresh and replay once. */
    private <T> T withAuthRetryOn401(Function<String, T> call) {
        String token = tokens.get();
        try {
            return call.apply(token);
        } catch (UnauthorizedException first) {
            log.info("Notification API returned 401 for SMS — refreshing token and replaying once");
            try {
                return call.apply(tokens.refreshAfterRejection(token));
            } catch (UnauthorizedException second) {
                throw new NotificationDeliveryException(
                        "Notification API rejected our credentials twice (401) — check BANK_API_USERNAME/PASSWORD/KEY");
            }
        }
    }

    /** One login. Runs outside any lock; {@link #tokens} makes it single-flight. */
    private SingleFlightTokenCache.Token login() {
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
            String accessToken = token.toString();
            Instant expiresAt = deriveExpiry(accessToken);
            log.info("Notification API login succeeded; token valid until {}", expiresAt);
            return new SingleFlightTokenCache.Token(accessToken, expiresAt);
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

    /**
     * Whether this cell can send SMS at all — all four notification-API
     * settings present. The support message endpoints ask BEFORE they write a
     * row, so an unprovisioned cell answers 503 instead of recording an attempt
     * that could never have been made.
     */
    public boolean isConfigured() {
        return !isBlank(properties.getBaseUrl()) && !isBlank(properties.getApiKey())
                && !isBlank(properties.getUsername()) && !isBlank(properties.getPassword());
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
