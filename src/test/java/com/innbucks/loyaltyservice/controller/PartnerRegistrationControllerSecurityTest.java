package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The partner registration endpoint is OFF in the test profile, exactly as it
 * is on a cell that has not opted in — so what this class pins is the disabled
 * posture, which is the one every production cell runs today.
 *
 * <p>Every assertion uses a specific status. {@code is4xxClientError()} would
 * pass on a Spring Security 401 raised before the controller ever ran, which is
 * how a missing {@code permitAll} entry hides until CI on another machine.
 * Distinguishing 404 (switched off) from 401 (credential rejected) is the whole
 * point of these cases.
 *
 * <p>The enabled paths — valid assertion accepted, bad key refused, half-provisioned
 * 503 — are exercised by {@code RegistrationAssertionVerifierTest} at the unit
 * level and would need a second Spring context with the feature switched on to
 * assert end to end. Deliberately not done here: a nested {@code @SpringBootTest}
 * with different properties evicts the shared context and slows the whole suite,
 * for coverage the verifier tests already give.
 */
class PartnerRegistrationControllerSecurityTest extends ControllerSecurityTestBase {

    private static final String PATH = "/loyalty/partner/registrations";

    @Test
    @DisplayName("disabled cell answers 404 — indistinguishable from no such route")
    void disabled_returns404() throws Exception {
        // Not 403 and not 503: a cell that never opted in should not even admit
        // the endpoint exists.
        mockMvc.perform(post(PATH)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"assertion\":\"whatever\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a partner key on a disabled cell changes nothing")
    void disabled_withKey_returns404() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Partner-Key", "some-key-that-does-not-matter-here")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+263771234567\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("reached the controller, not Spring Security — the 404 carries the ApiResult envelope")
    void disabled_returnsTheDomainEnvelope() throws Exception {
        // This is what separates "the controller ran and refused" from "Spring
        // Security rejected the request before the controller existed". If the
        // permitAll entry were missing, this would be a bare 401 with the
        // security config's fixed body instead.
        mockMvc.perform(post(PATH)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"assertion\":\"whatever\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404 NOT_FOUND"));
    }

    @Test
    @DisplayName("a fleet JWT grants nothing here")
    void jwt_isNotACredentialForThisEndpoint() throws Exception {
        // The endpoint authenticates a PARTNER, not a user. A valid admin token
        // must not be a way in — and the JwtFilter skips this prefix entirely,
        // so a stale Authorization header must not 401 it either.
        mockMvc.perform(post(PATH)
                        .header("Authorization", bearer(jwt("admin@test.local", "SUPER_ADMIN")))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"assertion\":\"whatever\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET is not mapped — the permitAll is scoped to POST")
    void get_isNotPermitted() throws Exception {
        // The SecurityConfig entry names POST + the exact path rather than the
        // /loyalty/partner prefix, so anything else under that prefix stays
        // closed by default instead of inheriting the exemption.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PATH))
                .andExpect(status().isUnauthorized());
    }
}
