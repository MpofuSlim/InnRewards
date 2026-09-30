package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.integration.NotificationDeliveryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Record-bound customer messaging, end to end against real Postgres with the
 * two gateway clients mocked.
 *
 * <p>The properties pinned here are the contract's: the recipient is only ever
 * the looked-up phone; a channel the cell cannot send on is a 503 that writes
 * nothing; limits count rows and refuse with 429 naming the scope; SMS falls
 * back to WhatsApp only when asked to; total failure is a 502 carrying the
 * FAILED record; a voucher resend never stores or returns the code; and every
 * response masks the phone.
 */
class SupportMessageFlowTest extends SupportTestBase {

    private record Ctx(String phone, UUID agent, String token, UUID lookupId, Seeded seeded) {
        String path() {
            return "/loyalty/support/lookups/" + lookupId + "/messages";
        }

        String masked() {
            return "****" + phone.substring(phone.length() - 4);
        }
    }

    private Ctx customer() throws Exception {
        String phone = randomPhone();
        Seeded s = seedCustomer(phone);
        UUID agent = UUID.randomUUID();
        String token = agentToken(agent);
        return new Ctx(phone, agent, token, lookup(token, phone), s);
    }

    private static String body(String channel, String text) {
        return "{\"channel\":\"" + channel + "\",\"body\":\"" + text + "\"}";
    }

    private long rowsTo(String phone) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM support_message WHERE recipient_msisdn = ?",
                Long.class, phone);
    }

    @Test
    @DisplayName("preview: transliterated SMS text, its length and segments — and it writes nothing")
    void preview_reportsAndWritesNothing() throws Exception {
        Ctx c = customer();

        mockMvc.perform(post(c.path() + "/preview").header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SMS", "Your refund is done: see innbucks.co.zw/help")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("SMS"))
                .andExpect(jsonPath("$.data.recipientRole").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.recipient").value(c.masked()))
                .andExpect(jsonPath("$.data.text").value("Your refund is done see innbucks.co.zw help\n- InnBucks Loyalty Support"))
                .andExpect(jsonPath("$.data.characters").value(70))
                .andExpect(jsonPath("$.data.maxCharacters").value(459))
                .andExpect(jsonPath("$.data.smsSegments").value(1))
                .andExpect(jsonPath("$.data.transliterated").value(true));

        assertThat(rowsTo(c.phone())).isZero();
        assertThat(actionsOf(c.agent())).containsExactly("CUSTOMER_LOOKUP");
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("preview reports an over-long text instead of refusing it; send refuses it with 400 message_too_long")
    void overLong_previewReports_sendRefuses() throws Exception {
        Ctx c = customer();
        String longText = "x".repeat(470);

        mockMvc.perform(post(c.path() + "/preview").header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS", longText)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.characters").value(470 + 1 + "- InnBucks Loyalty Support".length()))
                .andExpect(jsonPath("$.data.smsSegments").value(4));
        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS", longText)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("message_too_long"))
                .andExpect(jsonPath("$.data.channel").value("SMS"))
                .andExpect(jsonPath("$.data.maxCharacters").value(459));
        assertThat(rowsTo(c.phone())).isZero();
    }

    @Test
    @DisplayName("SMS: 201, sent to the LOOKED-UP phone, text stored exactly, phone masked, MESSAGE_SENT logged")
    void sms_sent() throws Exception {
        Ctx c = customer();

        MvcResult r = mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SMS", "Your points are back — sorry!")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.outcome").value("SENT"))
                .andExpect(jsonPath("$.data.deliveredVia").value("SMS"))
                .andExpect(jsonPath("$.data.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.data.recipient").value(c.masked()))
                .andExpect(jsonPath("$.data.sentBy.uuid").value(c.agent().toString()))
                .andReturn();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq(c.phone()), text.capture(), startsWith("SUPPORT-"));
        assertThat(text.getValue()).isEqualTo("Your points are back - sorry.\n- InnBucks Loyalty Support");
        assertThat(body(r).path("data").path("text").asText()).isEqualTo(text.getValue());
        assertThat(r.getResponse().getContentAsString()).doesNotContain(c.phone());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT outcome, delivered_via, body, kind, recipient_role, completed_at FROM support_message "
                        + "WHERE recipient_msisdn = ?", c.phone());
        assertThat(row.get("outcome")).isEqualTo("SENT");
        assertThat(row.get("delivered_via")).isEqualTo("SMS");
        assertThat(row.get("body")).isEqualTo(text.getValue());
        assertThat(row.get("recipient_role")).isEqualTo("CUSTOMER");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(actionsOf(c.agent())).containsExactly("CUSTOMER_LOOKUP", "MESSAGE_SENT");
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
    }

    @Test
    @DisplayName("a link to a host not on the allow-list is 400 link_not_allowed, naming it; nothing is written")
    void disallowedLink_is400() throws Exception {
        Ctx c = customer();

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WHATSAPP", "Claim your prize at https://evil.example/claim")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("link_not_allowed"))
                .andExpect(jsonPath("$.data.host").value("evil.example"));
        assertThat(rowsTo(c.phone())).isZero();
    }

    @Test
    @DisplayName("a channel the cell has not provisioned is 503 channel_unavailable BEFORE any row is written")
    void unprovisionedChannel_is503_andWritesNothing() throws Exception {
        Ctx c = customer();
        when(whatsApp.isConfigured()).thenReturn(false);

        for (String channel : List.of("WHATSAPP", "SMS_THEN_WHATSAPP")) {
            mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                            .contentType(MediaType.APPLICATION_JSON).content(body(channel, "Hello")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("channel_unavailable"));
        }
        assertThat(rowsTo(c.phone())).isZero();
        assertThat(actionsOf(c.agent())).containsExactly("CUSTOMER_LOOKUP");
    }

    @Test
    @DisplayName("SMS_THEN_WHATSAPP: a failed SMS falls back to WhatsApp, which gets the ORIGINAL text")
    void smsFails_fallsBackToWhatsApp() throws Exception {
        Ctx c = customer();
        doThrow(new NotificationDeliveryException("gateway down")).when(sms).sendSms(eq(c.phone()), anyString(), anyString());

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SMS_THEN_WHATSAPP", "Sorry — fixed!")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.outcome").value("SENT"))
                .andExpect(jsonPath("$.data.deliveredVia").value("WHATSAPP"))
                .andExpect(jsonPath("$.data.text").value("Sorry — fixed!\n- InnBucks Loyalty Support"));

        verify(whatsApp).sendCustomNotification(c.phone(), "Sorry — fixed!\n- InnBucks Loyalty Support");
        assertThat(jdbc.queryForObject("SELECT body FROM support_message WHERE recipient_msisdn = ?",
                String.class, c.phone())).isEqualTo("Sorry — fixed!\n- InnBucks Loyalty Support");
    }

    @Test
    @DisplayName("SMS alone does NOT fall back: a failed SMS is a failed message")
    void smsOnly_doesNotFallBack() throws Exception {
        Ctx c = customer();
        doThrow(new NotificationDeliveryException("gateway down")).when(sms).sendSms(eq(c.phone()), anyString(), anyString());

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS", "Hello")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.data.failureCode").value("sms_failed"));
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
    }

    @Test
    @DisplayName("every channel failing is 502 message_not_delivered carrying the FAILED record, and the row says so")
    void allChannelsFail_is502_withTheRecord() throws Exception {
        Ctx c = customer();
        doThrow(new NotificationDeliveryException("sms down")).when(sms).sendSms(eq(c.phone()), anyString(), anyString());
        doThrow(new NotificationDeliveryException("wa down")).when(whatsApp).sendCustomNotification(eq(c.phone()), anyString());

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS_THEN_WHATSAPP", "Hello")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("message_not_delivered"))
                .andExpect(jsonPath("$.data.outcome").value("FAILED"))
                .andExpect(jsonPath("$.data.deliveredVia").doesNotExist())
                .andExpect(jsonPath("$.data.failureCode").value("sms_and_whatsapp_failed"))
                .andExpect(jsonPath("$.data.recipient").value(c.masked()));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT outcome, failure_code FROM support_message WHERE recipient_msisdn = ?", c.phone());
        assertThat(row.get("outcome")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("sms_and_whatsapp_failed");
        assertThat(actionsOf(c.agent())).containsExactly("CUSTOMER_LOOKUP", "MESSAGE_SENT");
    }

    @Test
    @DisplayName("the per-recipient limit (5 per 24h, every attempt) is 429 scope RECIPIENT and writes nothing")
    void perRecipientLimit_is429() throws Exception {
        Ctx c = customer();
        UUID otherAgent = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            // Attempts by OTHER agents, including a failed one, still count against the customer.
            seedMessageRow(otherAgent.toString(), c.phone(), i == 0 ? "FAILED" : "SENT");
        }

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS", "Hello")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("support_message_rate_limited"))
                .andExpect(jsonPath("$.data.scope").value("RECIPIENT"))
                .andExpect(jsonPath("$.data.limit").value(5))
                .andExpect(jsonPath("$.data.windowMinutes").value(1440));
        assertThat(rowsTo(c.phone())).isEqualTo(5);
        verify(sms, never()).sendSms(eq(c.phone()), anyString(), anyString());
    }

    @Test
    @DisplayName("the per-agent limit (60 per hour, to anyone) is 429 scope AGENT")
    void perAgentLimit_is429() throws Exception {
        Ctx c = customer();
        for (int i = 0; i < 60; i++) {
            seedMessageRow(c.agent().toString(), randomPhone(), "SENT");
        }

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS", "Hello")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.scope").value("AGENT"))
                .andExpect(jsonPath("$.data.limit").value(60))
                .andExpect(jsonPath("$.data.windowMinutes").value(60));
        assertThat(rowsTo(c.phone())).isZero();
    }

    @Test
    @DisplayName("an old attempt outside the window does not count")
    void attemptsOutsideTheWindow_doNotCount() throws Exception {
        Ctx c = customer();
        for (int i = 0; i < 5; i++) {
            seedMessageRow(UUID.randomUUID().toString(), c.phone(), "SENT");
        }
        jdbc.update("UPDATE support_message SET created_at = now() - interval '25 hours' WHERE recipient_msisdn = ?",
                c.phone());

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS", "Hello")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("voucher resend: the issue template, code included, to the HOLDER only; body NULL, text null")
    void voucherResend_neverStoresTheCode() throws Exception {
        Ctx c = customer();
        Voucher v = c.seeded().heldVoucher();
        String resend = "/loyalty/support/lookups/" + c.lookupId() + "/vouchers/" + v.getId() + "/resend";

        MvcResult r = mockMvc.perform(post(resend).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"WHATSAPP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("VOUCHER_RESEND"))
                .andExpect(jsonPath("$.data.recipientRole").value("VOUCHER_HOLDER"))
                .andExpect(jsonPath("$.data.text").doesNotExist())
                .andExpect(jsonPath("$.data.recipient").value(c.masked()))
                .andReturn();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq(c.phone()), sent.capture());
        String grouped = v.getCode().replaceAll("(.{4})(?!$)", "$1-");
        assertThat(sent.getValue()).as("the same template the issue flow sends").contains(grouped).startsWith("Hi Rudo");
        assertThat(r.getResponse().getContentAsString()).doesNotContain(v.getCode()).doesNotContain(grouped);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT body, subject_kind, subject_id FROM support_message WHERE recipient_msisdn = ?", c.phone());
        assertThat(row.get("body")).isNull();
        assertThat(row.get("subject_kind")).isEqualTo("VOUCHER");
        assertThat(row.get("subject_id")).isEqualTo(v.getId().toString());

        // ...and the history never shows it either.
        mockMvc.perform(get(c.path()).header("Authorization", bearer(c.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].kind").value("VOUCHER_RESEND"))
                .andExpect(jsonPath("$.data.content[0].text").doesNotExist());
    }

    @Test
    @DisplayName("resending a voucher the customer does not hold is 404; a spent one is 409; neither sends anything")
    void voucherResend_ownershipAndLiveness() throws Exception {
        Ctx c = customer();
        Seeded stranger = seedCustomer(randomPhone());
        String base = "/loyalty/support/lookups/" + c.lookupId() + "/vouchers/";

        mockMvc.perform(post(base + stranger.heldVoucher().getId() + "/resend")
                        .header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"SMS\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("voucher_not_found"));

        Voucher spent = c.seeded().heldVoucher();
        spent.setStatus(Voucher.Status.REDEEMED);
        voucherRepository.save(spent);
        mockMvc.perform(post(base + spent.getId() + "/resend")
                        .header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"SMS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("voucher_not_live"));

        assertThat(rowsTo(c.phone())).isZero();
        assertThat(rowsTo(stranger.membership().getPhoneNumber())).isZero();
    }

    @Test
    @DisplayName("the supervisor sees every agent's messages; an agent cannot")
    void oversight_isSupervisorOnly() throws Exception {
        Ctx c = customer();
        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("SMS", "Hello")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/loyalty/support/messages").param("agentUuid", c.agent().toString())
                        .header("Authorization", bearer(supervisorToken(UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].recipient").value(c.masked()));
        mockMvc.perform(get("/loyalty/support/messages").header("Authorization", bearer(c.token())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the recipient cannot be steered: an extra 'to' field in the body is ignored")
    void recipientCannotBeSteered() throws Exception {
        Ctx c = customer();
        String elsewhere = randomPhone();

        mockMvc.perform(post(c.path()).header("Authorization", bearer(c.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SMS\",\"body\":\"Hello\",\"to\":\"" + elsewhere
                                + "\",\"recipient\":\"" + elsewhere + "\"}"))
                .andExpect(status().isCreated());

        verify(sms).sendSms(eq(c.phone()), anyString(), anyString());
        verify(sms, never()).sendSms(eq(elsewhere), anyString(), anyString());
    }

    private void seedMessageRow(String agentUuid, String recipient, String outcome) {
        jdbc.update("""
                INSERT INTO support_message (id, subject_kind, subject_id, recipient_msisdn, recipient_role, kind,
                    channel_requested, delivered_via, outcome, body, agent_uuid, agent_login, created_at, completed_at)
                VALUES (?, 'PHONE', ?, ?, 'CUSTOMER', 'CUSTOM', 'SMS', ?, ?, 'seeded', ?, 'seed@example.com', now(), now())
                """, UUID.randomUUID(), recipient, recipient, "SENT".equals(outcome) ? "SMS" : null, outcome, agentUuid);
    }
}
