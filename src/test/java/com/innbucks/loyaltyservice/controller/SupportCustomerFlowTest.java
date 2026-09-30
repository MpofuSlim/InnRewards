package com.innbucks.loyaltyservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read side of customer support, end to end: the real filter chain, real
 * Postgres (V54 applied), real repositories.
 *
 * <p>What is pinned: a lookup is on-record-or-404 and is LOGGED either way; a
 * lookupId works for the agent who made it and nobody else, and only while
 * live; every drill-down writes its row; phones come back masked and voucher
 * codes never come back at all.
 */
class SupportCustomerFlowTest extends SupportTestBase {

    @Test
    @DisplayName("a phone on record: 201, a lookupId, the 360 — masked, code-free — and a CUSTOMER_LOOKUP row")
    void lookup_onRecord_returnsThe360() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        newOrder(s.tenantId(), s.merchantId(), phone, randomPhone());
        UUID agent = UUID.randomUUID();

        MvcResult r = mockMvc.perform(post("/loyalty/support/customers/lookup")
                        .header("Authorization", bearer(agentToken(agent)))
                        .contentType(MediaType.APPLICATION_JSON)
                        // typed the way a caller reads it out: national, with the trunk 0
                        .content("{\"phone\":\"" + national(phone) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("201 CREATED"))
                .andReturn();

        JsonNode data = body(r).path("data");
        JsonNode c = data.path("customer");
        assertThat(data.path("lookupId").asText()).isNotBlank();
        assertThat(data.path("expiresAt").asText()).isNotBlank();
        assertThat(c.path("phone").asText()).isEqualTo("****" + phone.substring(phone.length() - 4));
        assertThat(c.path("memberships")).hasSize(1);
        assertThat(c.path("memberships").get(0).path("userId").asText()).isEqualTo(s.membership().getId().toString());
        assertThat(c.path("memberships").get(0).path("tenantName").asText()).startsWith("Test Tenant");
        assertThat(c.path("wallet").path("totalBalance").decimalValue()).isEqualByComparingTo("1250");
        assertThat(c.path("recentTransactions").path("content")).hasSize(1);
        assertThat(c.path("recentTransactions").path("content").get(0).path("merchantName").asText())
                .startsWith("Example Pizza");
        assertThat(c.path("vouchers").path("heldLive").asLong()).isEqualTo(1);
        assertThat(c.path("vouchers").path("heldTotal").asLong()).isEqualTo(1);
        assertThat(c.path("vouchers").path("recentHeld").get(0).path("holder").asText()).startsWith("****");
        assertThat(c.path("voucherOrders").path("total").asLong()).isEqualTo(1);
        assertThat(c.path("voucherOrders").path("recent").get(0).path("roles").get(0).asText()).isEqualTo("PAYER");
        assertThat(c.path("tier").path("currentTier").asInt()).isEqualTo(1);

        String raw = r.getResponse().getContentAsString();
        assertThat(raw).doesNotContain(phone).doesNotContain(national(phone));
        assertThat(raw).as("a voucher code is a bearer credential").doesNotContain(s.heldVoucher().getCode());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, action, subject_kind, subject_id FROM support_activity WHERE agent_uuid = ?",
                agent.toString());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("action")).isEqualTo("CUSTOMER_LOOKUP");
            assertThat(row.get("subject_kind")).isEqualTo("PHONE");
            assertThat(row.get("subject_id")).isEqualTo(phone);
            assertThat(row.get("id").toString()).isEqualTo(data.path("lookupId").asText());
        });
    }

    @Test
    @DisplayName("a phone on NO record: 404 customer_not_found, and a SEARCH row that holds only the masked phone")
    void lookup_notOnRecord_is404_andStillLogged() throws Exception {
        String phone = randomPhone();
        UUID agent = UUID.randomUUID();

        mockMvc.perform(post("/loyalty/support/customers/lookup")
                        .header("Authorization", bearer(agentToken(agent)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("customer_not_found"));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action, subject_id, detail FROM support_activity WHERE agent_uuid = ?", agent.toString());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("action")).isEqualTo("SEARCH");
            assertThat(row.get("subject_id")).isNull();
            String detail = (String) row.get("detail");
            assertThat(detail).contains("\"found\":false").contains("****" + phone.substring(phone.length() - 4));
            assertThat(detail).doesNotContain(phone.substring(1));
        });
    }

    @Test
    @DisplayName("a phone only a voucher SENDER column knows, stored as typed, is on record")
    void lookup_senderOnly_typedNationally_isOnRecord() throws Exception {
        String sender = randomPhone();
        Seeded other = seedCustomer(randomPhone());
        Voucher gifted = newVoucher(other.tenantId(), other.merchantId(), other.membership().getPhoneNumber(),
                other.membership().getId(), national(sender));
        String token = agentToken(UUID.randomUUID());

        UUID lookupId = lookup(token, sender);

        MvcResult sent = mockMvc.perform(get("/loyalty/support/lookups/" + lookupId + "/vouchers")
                        .param("role", "SENT")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(gifted.getId().toString()))
                .andReturn();
        assertThat(sent.getResponse().getContentAsString()).doesNotContain(gifted.getCode());
    }

    @Test
    @DisplayName("an unparseable phone is 400 invalid_msisdn, and the input is not echoed")
    void lookup_badPhone_is400() throws Exception {
        mockMvc.perform(post("/loyalty/support/customers/lookup")
                        .header("Authorization", bearer(agentToken(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12ab-not-a-phone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_msisdn"))
                .andExpect(jsonPath("$.message").value("That is not a valid phone number."));
    }

    @Test
    @DisplayName("another agent's lookupId is 404 lookup_not_found — the same answer as a missing one")
    void lookup_ofAnotherAgent_is404() throws Exception {
        String phone = randomPhone();
        seedCustomer(phone);
        UUID lookupId = lookup(agentToken(UUID.randomUUID()), phone);
        String someoneElse = agentToken(UUID.randomUUID());

        MvcResult theirs = mockMvc.perform(get("/loyalty/support/lookups/" + lookupId)
                        .header("Authorization", bearer(someoneElse)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("lookup_not_found"))
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/loyalty/support/lookups/" + UUID.randomUUID())
                        .header("Authorization", bearer(someoneElse)))
                .andExpect(status().isNotFound())
                .andReturn();

        // Byte-identical, so the endpoint cannot tell an agent a colleague's lookup exists.
        assertThat(theirs.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("an expired lookup is 404 lookup_not_found for its own agent too")
    void lookup_expired_is404() throws Exception {
        String phone = randomPhone();
        seedCustomer(phone);
        UUID agent = UUID.randomUUID();
        UUID lookupId = lookup(agentToken(agent), phone);
        // Age it past the 12h TTL. (The application cannot rewrite this table; the test can.)
        maintenanceUpdate("UPDATE support_activity SET created_at = now() - interval '13 hours' "
                + "WHERE id = '" + lookupId + "'");

        mockMvc.perform(get("/loyalty/support/lookups/" + lookupId + "/transactions")
                        .header("Authorization", bearer(agentToken(agent))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("lookup_not_found"));
    }

    @Test
    @DisplayName("every drill-down answers, pages, and writes its own VIEW_* row")
    void drillDowns_answerAndAreLogged() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        newOrder(s.tenantId(), s.merchantId(), randomPhone(), national(phone));
        UUID agent = UUID.randomUUID();
        String token = agentToken(agent);
        UUID lookupId = lookup(token, phone);
        String base = "/loyalty/support/lookups/" + lookupId;

        mockMvc.perform(get(base).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lookupId").value(lookupId.toString()));
        mockMvc.perform(get(base + "/transactions").param("size", "500").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.content[0].id").value(s.transaction().getId().toString()));
        mockMvc.perform(get(base + "/ledger").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get(base + "/vouchers").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(s.heldVoucher().getId().toString()))
                .andExpect(jsonPath("$.data.content[0].code").doesNotExist());
        mockMvc.perform(get(base + "/vouchers").param("role", "TRANSFERRED").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get(base + "/vouchers").param("role", "BOGUS").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(base + "/voucher-orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].roles[0]").value("RECIPIENT"))
                .andExpect(jsonPath("$.data.content[0].recipient").value("****" + phone.substring(phone.length() - 4)));
        mockMvc.perform(get(base + "/notes").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertThat(actionsOf(agent)).containsExactlyInAnyOrder(
                "CUSTOMER_LOOKUP", "VIEW_CUSTOMER", "VIEW_TRANSACTIONS", "VIEW_LEDGER",
                "VIEW_VOUCHERS", "VIEW_VOUCHERS", "VIEW_VOUCHER_ORDERS", "VIEW_NOTES");
    }

    @Test
    @DisplayName("a customer with memberships in two tenants is ONE customer: both memberships, one statement")
    void crossTenant_isOneCustomer() throws Exception {
        String phone = randomPhone();
        Seeded first = seedCustomer(phone);
        UUID secondTenant = newTenant("support-two");
        UUID secondMerchant = newMerchant(secondTenant, "Example Grocer").getId();
        LoyaltyUser second = newMembership(secondTenant, phone, LoyaltyUser.Status.BLOCKED);
        newTransaction(secondTenant, secondMerchant, second.getId(), new java.math.BigDecimal("40"));
        String token = agentToken(UUID.randomUUID());

        MvcResult r = mockMvc.perform(post("/loyalty/support/customers/lookup")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode c = body(r).path("data").path("customer");
        assertThat(c.path("memberships")).hasSize(2);
        assertThat(c.path("memberships").findValuesAsText("status")).containsExactlyInAnyOrder("ACTIVE", "BLOCKED");
        assertThat(c.path("recentTransactions").path("totalElements").asLong()).isEqualTo(2);
        assertThat(first.tenantId()).isNotEqualTo(secondTenant);
    }

    @Test
    @DisplayName("notes: HTML stripped, attributed, newest first, append-only; an empty one is 400")
    void notes_areAppendOnlyAndAttributed() throws Exception {
        String phone = randomPhone();
        seedCustomer(phone);
        UUID agent = UUID.randomUUID();
        String token = agentToken(agent);
        UUID lookupId = lookup(token, phone);
        String notes = "/loyalty/support/lookups/" + lookupId + "/notes";

        mockMvc.perform(post(notes).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"<b>Called</b> about a missing voucher\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.body").value("Called about a missing voucher"))
                .andExpect(jsonPath("$.data.subjectKind").value("PHONE"))
                .andExpect(jsonPath("$.data.subjectId").value("****" + phone.substring(phone.length() - 4)))
                .andExpect(jsonPath("$.data.createdBy.uuid").value(agent.toString()));
        mockMvc.perform(post(notes).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   <p> </p>  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_note_body"));

        mockMvc.perform(get(notes).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        // There is no route to change or remove a note.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(notes).header("Authorization", bearer(token)))
                .andExpect(status().isMethodNotAllowed());
        assertThat(actionsOf(agent)).contains("NOTE_ADDED");
    }

    @Test
    @DisplayName("the supervisor feed filters by agent and action, masks phones, and refuses an inverted range")
    void activityFeed_forSupervisors() throws Exception {
        String phone = randomPhone();
        seedCustomer(phone);
        UUID agent = UUID.randomUUID();
        lookup(agentToken(agent), phone);
        String supervisor = supervisorToken(UUID.randomUUID());

        mockMvc.perform(get("/loyalty/support/activity")
                        .param("agentUuid", agent.toString())
                        .param("action", "CUSTOMER_LOOKUP")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].agent.uuid").value(agent.toString()))
                .andExpect(jsonPath("$.data.content[0].subjectId").value("****" + phone.substring(phone.length() - 4)))
                .andExpect(jsonPath("$.data.content[0].detail.found").value(true));
        mockMvc.perform(get("/loyalty/support/activity")
                        .param("from", "2026-09-30T10:00:00Z").param("to", "2026-09-30T09:00:00Z")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_range"));
        mockMvc.perform(get("/loyalty/support/activity").param("action", "NOPE")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isBadRequest());
    }
}
