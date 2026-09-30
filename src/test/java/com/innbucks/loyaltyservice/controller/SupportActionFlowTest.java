package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.LoyaltyRefreshToken;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.security.SupportPermissions;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The agent and supervisor actions, end to end on real Postgres.
 *
 * <p>Pinned: every target must belong to the looked-up phone (anyone else's is
 * a 404, never an action on the wrong customer); the reused rules keep their
 * semantics — the adjustment ceilings apply to a supervisor and count against
 * THEM, a reversal cannot happen twice, unblock is BLOCKED-only; and each
 * action leaves exactly one activity row, with any typed reason in a note, not
 * in the row.
 */
class SupportActionFlowTest extends SupportTestBase {

    @Autowired private LoyaltyRefreshTokenRepository refreshTokens;

    private String url(UUID lookupId, String tail) {
        return "/loyalty/support/lookups/" + lookupId + tail;
    }

    private static String json(String s) {
        return s.replace('\'', '"');
    }

    // ---- sign-out ----

    @Test
    @DisplayName("sign-out revokes every live chain of the phone, keeps the reason as a note, logs SESSIONS_REVOKED")
    void signOut_revokesEveryChain() throws Exception {
        String phone = randomPhone();
        seedCustomer(phone);
        seedChain(phone, false);
        seedChain(phone, false);
        seedChain(phone, true);                       // already revoked: not "active", untouched
        String otherPhone = randomPhone();
        seedChain(otherPhone, false);                 // someone else's session survives
        UUID agent = UUID.randomUUID();
        String token = agentToken(agent);
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/sign-out")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'reason':'Customer reports a lost phone'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainsRevoked").value(2))
                .andExpect(jsonPath("$.data.tokensRevoked").value(2))
                .andExpect(jsonPath("$.data.noteId").isNotEmpty());

        assertThat(refreshTokens.countActiveChains(phone, Instant.now())).isZero();
        assertThat(refreshTokens.countActiveChains(otherPhone, Instant.now())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT DISTINCT revoked_reason FROM loyalty_refresh_tokens "
                        + "WHERE phone_number = ? AND revoked_reason LIKE 'support:%'", String.class, phone))
                .containsExactly("support:" + agent);
        assertThat(jdbc.queryForObject("SELECT body FROM support_note WHERE subject_id = ?", String.class, phone))
                .contains("Customer reports a lost phone").contains("2 active");
        String detail = jdbc.queryForObject("SELECT detail FROM support_activity WHERE agent_uuid = ? "
                + "AND action = 'SESSIONS_REVOKED'", String.class, agent.toString());
        assertThat(detail).as("never the typed reason").doesNotContain("lost phone").contains("noteId");
    }

    // ---- adjust ----

    @Test
    @DisplayName("a supervisor adjustment credits the customer, is posted BY the supervisor, and is logged")
    void adjust_credits() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        UUID supervisor = UUID.randomUUID();
        String token = supervisorToken(supervisor);
        UUID lookupId = lookup(token, phone);

        MvcResult r = mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId()
                                + "','points':250,'reason':'Goodwill credit'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data.postedBy").value(supervisor.toString()))
                .andExpect(jsonPath("$.data.reference").value("Goodwill credit"))
                .andReturn();

        UUID txnId = UUID.fromString(body(r).path("data").path("id").asText());
        LoyaltyTransaction t = transactionRepository.findById(txnId).orElseThrow();
        assertThat(t.getPointsDelta()).isEqualByComparingTo("250");
        assertThat(t.getUserId()).isEqualTo(s.membership().getId());
        assertThat(t.getPostedBy()).isEqualTo(supervisor);
        assertThat(actionsOf(supervisor)).containsExactly("CUSTOMER_LOOKUP", "POINTS_ADJUSTED");
    }

    @Test
    @DisplayName("a membership of a DIFFERENT phone is 404 membership_not_found — never an adjustment to the wrong customer")
    void adjust_otherCustomersMembership_is404() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        Seeded stranger = seedCustomer(randomPhone());
        String token = supervisorToken(UUID.randomUUID());
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + stranger.membership().getId() + "','merchantId':'"
                                + stranger.merchantId() + "','points':10,'reason':'x'}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("membership_not_found"));
        // A merchant from another programme is refused too.
        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'"
                                + stranger.merchantId() + "','points':10,'reason':'x'}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("merchant_not_found"));
        assertThat(transactionRepository.findTop50ByUserIdOrderByCreatedAtDesc(stranger.membership().getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("the per-adjustment ceiling applies to a supervisor: 5001 is 403 ADJUSTMENT_LIMIT_EXCEEDED")
    void adjust_perAdjustmentCeiling_appliesToSupervisors() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        String token = supervisorToken(UUID.randomUUID());
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId()
                                + "','points':5001,'reason':'too much'}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_LIMIT_EXCEEDED"));
        assertThat(transactionRepository.findTop50ByUserIdOrderByCreatedAtDesc(s.membership().getId())).hasSize(1);
    }

    @Test
    @DisplayName("the daily ceiling counts the SUPERVISOR's own 24h total, across customers")
    void adjust_dailyCeiling_countsTheSupervisor() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        UUID supervisor = UUID.randomUUID();
        // 19,900 already posted today by this supervisor, on somebody else.
        Seeded other = seedCustomer(randomPhone());
        LoyaltyTransaction earlier = new LoyaltyTransaction();
        earlier.setTenantId(other.tenantId());
        earlier.setMerchantId(other.merchantId());
        earlier.setUserId(other.membership().getId());
        earlier.setType(TransactionType.ADJUSTMENT);
        earlier.setPointsDelta(new BigDecimal("19900"));
        earlier.setPostedBy(supervisor);
        transactionRepository.save(earlier);
        String token = supervisorToken(supervisor);
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId()
                                + "','points':200,'reason':'one more'}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_DAILY_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("zero or fractional points are 400; an over-long reason is 400 before anything is written")
    void adjust_validation() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        String token = supervisorToken(UUID.randomUUID());
        UUID lookupId = lookup(token, phone);
        String prefix = "{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId() + "',";

        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(prefix + "'points':0,'reason':'x'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_points"));
        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(prefix + "'points':2.5,'reason':'x'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.points").exists());
        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(prefix + "'points':5,'reason':'" + "r".repeat(97) + "'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").exists());
        assertThat(transactionRepository.findTop50ByUserIdOrderByCreatedAtDesc(s.membership().getId())).hasSize(1);
    }

    @Test
    @DisplayName("a supervisor token with no userUuid cannot move points: 403 agent_identity_required")
    void adjust_withoutUserUuid_is403() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        String token = TestJwtFactory.builder("legacy-supervisor@example.com").role("SUPPORT_SUPERVISOR")
                .permissions(SUPERVISOR_PERMS).sign(jwtSecret);
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId()
                                + "','points':5,'reason':'x'}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("agent_identity_required"));
    }

    @Test
    @DisplayName("an AGENT (manage, no supervise) cannot adjust")
    void adjust_agentIsForbidden() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        String token = agentToken(UUID.randomUUID());
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId()
                                + "','points':5,'reason':'x'}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"));
    }

    // ---- reverse ----

    @Test
    @DisplayName("reverse: the customer's own transaction reverses once, then 409; another phone's is 404")
    void reverse_ownOnly_once() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        Seeded stranger = seedCustomer(randomPhone());
        UUID supervisor = UUID.randomUUID();
        String token = supervisorToken(supervisor);
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/transactions/" + stranger.transaction().getId() + "/reverse"))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'wrong customer'}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("transaction_not_found"));

        mockMvc.perform(post(url(lookupId, "/transactions/" + s.transaction().getId() + "/reverse"))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'Duplicate earn'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postedBy").value(supervisor.toString()));
        mockMvc.perform(post(url(lookupId, "/transactions/" + s.transaction().getId() + "/reverse"))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'again'}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"));

        assertThat(transactionRepository.findById(s.transaction().getId()).orElseThrow().getStatus())
                .isEqualTo(LoyaltyTransaction.Status.REVERSED);
        assertThat(transactionRepository.findById(stranger.transaction().getId()).orElseThrow().getStatus())
                .isEqualTo(LoyaltyTransaction.Status.POSTED);
        assertThat(actionsOf(supervisor)).containsExactly("CUSTOMER_LOOKUP", "TRANSACTION_REVERSED");
    }

    @Test
    @DisplayName("an adjustment made through support can be reversed through support: the 96-char cap keeps REV- in range")
    void adjustThenReverse_withAMaximalReason() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        String token = supervisorToken(UUID.randomUUID());
        UUID lookupId = lookup(token, phone);
        String reason = "r".repeat(96);

        MvcResult adjusted = mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId()
                                + "','points':40,'reason':'" + reason + "'}")))
                .andExpect(status().isOk())
                .andReturn();
        String txnId = body(adjusted).path("data").path("id").asText();

        MvcResult reversed = mockMvc.perform(post(url(lookupId, "/transactions/" + txnId + "/reverse"))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'" + "q".repeat(192) + "'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reference").value("REV-" + reason))
                .andReturn();
        assertThat(body(reversed).path("data").path("pointsDelta").decimalValue()).isEqualByComparingTo("-40");
    }

    // ---- unblock ----

    @Test
    @DisplayName("unblock: a BLOCKED membership of the customer goes ACTIVE with a note; an ACTIVE one is 409")
    void unblock_blockedOnly() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        UUID secondTenant = newTenant("support-blocked");
        LoyaltyUser blocked = newMembership(secondTenant, phone, LoyaltyUser.Status.BLOCKED);
        UUID supervisor = UUID.randomUUID();
        String token = supervisorToken(supervisor);
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/memberships/" + s.membership().getId() + "/unblock"))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'x'}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NOT_BLOCKED"));

        mockMvc.perform(post(url(lookupId, "/memberships/" + blocked.getId() + "/unblock"))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'reason':'Verified by phone; shared-device false positive'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.membership.userId").value(blocked.getId().toString()))
                .andExpect(jsonPath("$.data.noteId").isNotEmpty());

        assertThat(loyaltyUserRepository.findById(blocked.getId()).orElseThrow().getStatus())
                .isEqualTo(LoyaltyUser.Status.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT body FROM support_note WHERE subject_id = ?", String.class, phone))
                .contains("shared-device false positive");
        // The refused unblock left nothing behind: one row per action that HAPPENED.
        assertThat(actionsOf(supervisor)).containsExactly("CUSTOMER_LOOKUP", "MEMBERSHIP_UNBLOCKED");
    }

    @Test
    @DisplayName("unblocking a membership of a different phone is 404, and it stays blocked")
    void unblock_otherCustomersMembership_is404() throws Exception {
        String phone = randomPhone();
        seedCustomer(phone);
        String strangerPhone = randomPhone();
        LoyaltyUser strangerBlocked = newMembership(newTenant("support-stranger"), strangerPhone,
                LoyaltyUser.Status.BLOCKED);
        String token = supervisorToken(UUID.randomUUID());
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/memberships/" + strangerBlocked.getId() + "/unblock"))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json("{'reason':'x'}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("membership_not_found"));
        assertThat(loyaltyUserRepository.findById(strangerBlocked.getId()).orElseThrow().getStatus())
                .isEqualTo(LoyaltyUser.Status.BLOCKED);
    }

    @Test
    @DisplayName("SUPER_ADMIN (with the expanded perms) stays exempt from the adjustment ceiling")
    void superAdmin_remainsExempt() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        String token = TestJwtFactory.builder("owner@example.com").role("SUPER_ADMIN").userId(UUID.randomUUID())
                .permissions(List.of(SupportPermissions.READ, SupportPermissions.MANAGE,
                        SupportPermissions.SUPERVISE, SupportPermissions.SEND_MESSAGES))
                .sign(jwtSecret);
        UUID lookupId = lookup(token, phone);

        mockMvc.perform(post(url(lookupId, "/points/adjust")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'userId':'" + s.membership().getId() + "','merchantId':'" + s.merchantId()
                                + "','points':6000,'reason':'Platform correction'}")))
                .andExpect(status().isOk());
    }

    private void seedChain(String phone, boolean revoked) {
        LoyaltyRefreshToken row = new LoyaltyRefreshToken();
        row.setTokenHash(Long.toHexString(ThreadLocalRandom.current().nextLong())
                + UUID.randomUUID().toString().replace("-", ""));
        row.setPhoneNumber(phone);
        row.setChainId(UUID.randomUUID());
        row.setOriginScope("loyalty-otp");
        row.setIssuedAt(Instant.now());
        row.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        if (revoked) {
            row.setRevokedAt(Instant.now());
            row.setRevokedReason("signed_out");
        }
        refreshTokens.save(row);
    }
}
