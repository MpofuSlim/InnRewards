package com.innbucks.loyaltyservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.config.CorrelationIdPropagatingInterceptor;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.UserServiceApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Read-side client into user-service. Loyalty-service is NOT the system of
// record for users — it asks user-service whether a phone number resolves to
// a real customer before lazily creating its local LoyaltyUser projection.
@Component
@Slf4j
public class UserServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public UserServiceClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${user-service.base-url:http://user-service}") String baseUrl,
            @Value("${user-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${user-service.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        // Clone the load-balanced builder so "user-service" resolves through
        // Eureka; clone() preserves the LB interceptor alongside our per-client
        // request factory and correlation-id interceptor.
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    public Optional<CustomerTierResponseDTO> getCustomerTier(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        try {
            String uri = UriComponentsBuilder.fromPath("/auth/customer/tier")
                    .queryParam("phoneNumber", phoneNumber)
                    .build()
                    .toUriString();
            String body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Optional.empty();
            }
            UserServiceApiResult<CustomerTierResponseDTO> envelope = objectMapper.readValue(
                    body,
                    new TypeReference<UserServiceApiResult<CustomerTierResponseDTO>>() {}
            );
            return Optional.ofNullable(envelope.data());
        } catch (Exception e) {
            log.warn("user-service tier lookup failed phoneNumber={} cause={}", MsisdnMasking.mask(phoneNumber), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Returns the set of {@code loyalty_merchant_id}s that already have at
     * least one user carrying the given role (in practice always
     * {@code MERCHANT_ADMIN}). Backs the
     * {@code GET /loyalty/merchants?unassigned=true} filter — the result is
     * the deny-list excluded from the page so the FE can show a registering
     * admin only merchants still up for grabs.
     *
     * <p>Authenticated with the shared {@code X-Internal-Token} (the same
     * secret event-service uses on its internal calls). The endpoint is
     * hidden from public Swagger and denied at the gateway edge.
     *
     * <p>Throws {@link IllegalStateException} on any failure — the caller is
     * the unassigned-filter path, where an empty fallback would silently show
     * every merchant (including ones that already have admins) and defeat
     * the whole point of the picker. Better to surface 503 to the FE so it
     * can retry / show an error.
     */
    public Set<UUID> assignedMerchantIds() {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "innbucks.internal-api-token not configured; cannot call user-service /merchants/assigned");
        }
        try {
            String body = restClient.get()
                    .uri("/users/internal/merchants/assigned?role=MERCHANT_ADMIN")
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Collections.emptySet();
            }
            UserServiceApiResult<List<String>> envelope = objectMapper.readValue(
                    body, new TypeReference<UserServiceApiResult<List<String>>>() {});
            if (envelope == null || envelope.data() == null) {
                return Collections.emptySet();
            }
            Set<UUID> out = new LinkedHashSet<>();
            for (String s : envelope.data()) {
                if (s == null || s.isBlank()) continue;
                try {
                    out.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    log.warn("user-service returned a non-UUID assigned merchant id: '{}'", s);
                }
            }
            return out;
        } catch (Exception e) {
            // One catch covers both: restClient throws RuntimeException on the
            // HTTP path; ObjectMapper.readValue throws checked IOException on
            // parse failures. The unassigned-merchants picker treats both the
            // same way — surface so the controller can map to 503. A silent
            // empty fallback would show the FE every merchant (including
            // already-claimed ones) and defeat the whole picker.
            log.warn("user-service /merchants/assigned lookup failed cause={}", e.toString());
            throw new IllegalStateException("user-service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a user's contact details (phone / email / first name) by their
     * stable {@code user_uuid} via user-service's
     * {@code GET /users/internal/{userUuid}/contact}. Authenticated with the
     * shared {@code X-Internal-Token} (mirrors {@link #assignedMerchantIds()}).
     *
     * <p>Unlike {@code assignedMerchantIds()}, this is <strong>best-effort</strong>:
     * the sole caller is the tenant-attach notifier, where a missing contact
     * just means "skip the you've-been-added ping". Any non-2xx (404 unknown
     * user, 401 misconfigured token), unreachable user-service, blank token, or
     * parse failure returns {@link Optional#empty()} and logs a warning — it
     * never throws, so it can never fail or delay the tenant-create 201.
     */
    public Optional<UserContact> getUserContact(UUID userUuid) {
        if (userUuid == null) {
            return Optional.empty();
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("innbucks.internal-api-token not configured; skipping user-service contact lookup for {}", userUuid);
            return Optional.empty();
        }
        try {
            String body = restClient.get()
                    .uri("/users/internal/{userUuid}/contact", userUuid)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Optional.empty();
            }
            UserServiceApiResult<UserContact> envelope = objectMapper.readValue(
                    body, new TypeReference<UserServiceApiResult<UserContact>>() {});
            if (envelope == null || envelope.data() == null) {
                return Optional.empty();
            }
            return Optional.of(envelope.data());
        } catch (Exception e) {
            // Best-effort: RestClient throws RuntimeException on any non-2xx or
            // connectivity failure; ObjectMapper throws on a parse failure. The
            // notifier treats them all the same — no contact, skip the ping.
            log.warn("user-service contact lookup failed userUuid={} cause={}", userUuid, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Loyalty-side projection of user-service's {@code UserContact} DTO. Trimmed
     * to only what the tenant-attach notifier consumes; unknown JSON fields fall
     * through as ignored.
     */
    public record UserContact(UUID userUuid, String phoneNumber, String email, String firstName) {}
}
