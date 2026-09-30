package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.security.SupportPermissions;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call each support endpoint is the whole contract, so every endpoint
 * is walked through the same five callers:
 *
 * <ol>
 *   <li>no token — 401;</li>
 *   <li>a staff token with no {@code perms} claim — 403;</li>
 *   <li>a SUPER_ADMIN <b>role</b> with no {@code perms} claim — 403. This is the
 *       case that proves the gate is a PERMISSION and not a role: a SUPER_ADMIN
 *       reaches support through the codes its {@code *} wildcard expands to at
 *       mint time, never through the role itself;</li>
 *   <li>a token whose {@code perms} claim is forged to look like a role
 *       ({@code ROLE_SUPER_ADMIN}) plus every OTHER support permission — 403;</li>
 *   <li>a token holding exactly the required permission — past the gate, which
 *       for these probes means the endpoint's own 404 (an unknown lookup or
 *       customer), not a 401/403.</li>
 * </ol>
 *
 * <p>Every assertion names a specific status: {@code is4xxClientError()} would
 * pass on a Spring Security 401 raised before the controller ran.
 */
class SupportSecurityTest extends SupportTestBase {

    static Stream<Arguments> endpoints() {
        String l = "/loyalty/support/lookups/" + UUID.randomUUID();
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/loyalty/support/customers/lookup",
                        "{\"phone\":\"" + randomPhone() + "\"}", SupportPermissions.READ, 404),
                Arguments.of(HttpMethod.GET, l, null, SupportPermissions.READ, 404),
                Arguments.of(HttpMethod.GET, l + "/transactions", null, SupportPermissions.READ, 404),
                Arguments.of(HttpMethod.GET, l + "/ledger", null, SupportPermissions.READ, 404),
                Arguments.of(HttpMethod.GET, l + "/vouchers", null, SupportPermissions.READ, 404),
                Arguments.of(HttpMethod.GET, l + "/voucher-orders", null, SupportPermissions.READ, 404),
                Arguments.of(HttpMethod.GET, l + "/notes", null, SupportPermissions.READ, 404),
                Arguments.of(HttpMethod.POST, l + "/notes", "{\"body\":\"hello\"}", SupportPermissions.MANAGE, 404),
                Arguments.of(HttpMethod.GET, "/loyalty/support/activity", null, SupportPermissions.SUPERVISE, 200));
    }

    private MockHttpServletRequestBuilder call(HttpMethod method, String path, String body) {
        MockHttpServletRequestBuilder b = request(method, path);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return b;
    }

    private String staff(String role, List<String> perms) {
        TestJwtFactory.Builder b = TestJwtFactory.builder("staff-" + UUID.randomUUID() + "@example.com")
                .role(role).userId(UUID.randomUUID());
        if (perms != null) {
            b.permissions(perms);
        }
        return b.sign(jwtSecret);
    }

    @ParameterizedTest(name = "{0} {1} without a token is 401")
    @MethodSource("endpoints")
    void noToken_is401(HttpMethod method, String path, String body, String perm, int passes) throws Exception {
        mockMvc.perform(call(method, path, body))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} {1} with a permission-less staff token is 403")
    @MethodSource("endpoints")
    void noPerms_is403(HttpMethod method, String path, String body, String perm, int passes) throws Exception {
        mockMvc.perform(call(method, path, body).header("Authorization", bearer(staff("SHOP_ADMIN", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"));
    }

    @ParameterizedTest(name = "{0} {1} for a SUPER_ADMIN ROLE with no perms is 403 — the gate is a permission")
    @MethodSource("endpoints")
    void superAdminRoleWithoutPerms_is403(HttpMethod method, String path, String body, String perm, int passes)
            throws Exception {
        mockMvc.perform(call(method, path, body).header("Authorization", bearer(staff("SUPER_ADMIN", null))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} {1} with every OTHER permission (and a forged ROLE_ entry) is 403")
    @MethodSource("endpoints")
    void everyOtherPermission_is403(HttpMethod method, String path, String body, String perm, int passes)
            throws Exception {
        List<String> others = Stream.concat(
                        Stream.of(SupportPermissions.READ, SupportPermissions.MANAGE, SupportPermissions.SUPERVISE,
                                        SupportPermissions.SEND_MESSAGES)
                                .filter(p -> !p.equals(perm)),
                        Stream.of("ROLE_SUPER_ADMIN", "marketplace-support:supervise"))
                .toList();
        mockMvc.perform(call(method, path, body).header("Authorization", bearer(staff("CUSTOMER", others))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} {1} with exactly its permission gets past the gate")
    @MethodSource("endpoints")
    void exactPermission_passesTheGate(HttpMethod method, String path, String body, String perm, int passes)
            throws Exception {
        mockMvc.perform(call(method, path, body).header("Authorization", bearer(staff("SUPPORT_AGENT", List.of(perm)))))
                .andExpect(status().is(passes));
    }
}
