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
     * The email addresses of the people who run an organization (its OWNER and
     * ADMIN members with an active account), via user-service's
     * {@code GET /users/internal/organizations/{id}/admins}. Used to send a
     * merchant's invoice to the business that owns it — the recipient used to be
     * the merchant's {@code admin_email}, the binding this service retired.
     *
     * <p>Best-effort: the only caller is the after-commit invoice mailer, so a
     * blank token, a non-2xx (401, or a user-service too old to serve the
     * endpoint), an outage or a parse failure is an empty list and a warning,
     * never an exception. The invoice exists regardless and is on the billing
     * page. Blank emails (a phone-only account) are skipped.
     */
    public List<String> organizationAdminEmails(UUID organizationId) {
        if (organizationId == null) {
            return List.of();
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("innbucks.internal-api-token not configured; skipping organization-admin lookup for {}",
                    organizationId);
            return List.of();
        }
        try {
            String body = restClient.get()
                    .uri("/users/internal/organizations/{id}/admins", organizationId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return List.of();
            }
            UserServiceApiResult<List<OrganizationAdmin>> envelope = objectMapper.readValue(
                    body, new TypeReference<UserServiceApiResult<List<OrganizationAdmin>>>() {});
            if (envelope == null || envelope.data() == null) {
                return List.of();
            }
            return envelope.data().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(OrganizationAdmin::email)
                    .filter(e -> e != null && !e.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("user-service organization-admin lookup failed organizationId={} cause={}",
                    organizationId, e.toString());
            return List.of();
        }
    }

    /** One entry of user-service's organization-admins answer; unknown fields are ignored. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record OrganizationAdmin(UUID userUuid, String email) {}

    /**
     * Returns the phone numbers of every staff member linked to the given
     * merchant, via user-service's
     * {@code GET /users/internal/shop-staff/by-merchant/{merchantId}/contacts}
     * (shared {@code X-Internal-Token}).
     * Consumed by {@link com.innbucks.loyaltyservice.service.StaffRegistry}
     * for the earn-integrity {@code STAFF_RECIPIENT} guard.
     *
     * <p>The Optional carries the AUTHORITATIVE/UNKNOWN distinction the
     * fail-open guard depends on:
     * <ul>
     *   <li>{@code Optional.of(set)} — user-service answered; an EMPTY set is
     *       the authoritative "this merchant has no staff with phones".</li>
     *   <li>{@code Optional.empty()} — token unconfigured, transport failure,
     *       non-2xx, or an unparseable body: the answer is UNKNOWN and the
     *       registry degrades to guard-off rather than guessing.</li>
     * </ul>
     * Never throws — this sits under the earn path. Phoneless staff rows
     * (deliberately included by user-service so the userUuid survives for
     * pair reporting) are filtered here; whitespace is stripped so the set
     * matches {@code StaffRegistry.compareKey} form.
     */
    public Optional<Set<String>> merchantStaffPhones(UUID merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("innbucks.internal-api-token not configured; staff lookup for merchant {} "
                    + "is UNKNOWN (STAFF_RECIPIENT guard degraded)", merchantId);
            return Optional.empty();
        }
        try {
            String body = restClient.get()
                    .uri("/users/internal/shop-staff/by-merchant/" + merchantId + "/contacts")
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Optional.empty();
            }
            UserServiceApiResult<List<com.innbucks.loyaltyservice.dto.StaffContact>> envelope =
                    objectMapper.readValue(body,
                            new TypeReference<UserServiceApiResult<List<com.innbucks.loyaltyservice.dto.StaffContact>>>() {});
            if (envelope == null || envelope.data() == null) {
                return Optional.empty();
            }
            Set<String> phones = new LinkedHashSet<>();
            for (com.innbucks.loyaltyservice.dto.StaffContact c : envelope.data()) {
                if (c != null && c.phoneNumber() != null && !c.phoneNumber().isBlank()) {
                    phones.add(c.phoneNumber().replaceAll("\\s+", ""));
                }
            }
            return Optional.of(phones);
        } catch (Exception e) {
            log.warn("user-service staff lookup failed merchantId={} cause={}", merchantId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Resolves a user's contact details (phone / email / first name) by their
     * stable {@code user_uuid} via user-service's
     * {@code GET /users/internal/{userUuid}/contact}. Authenticated with the
     * shared {@code X-Internal-Token}.
     *
     * <p>This is <strong>best-effort</strong>:
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
