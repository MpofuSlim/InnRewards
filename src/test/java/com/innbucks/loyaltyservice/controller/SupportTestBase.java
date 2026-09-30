package com.innbucks.loyaltyservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.integration.SmsNotificationClient;
import com.innbucks.loyaltyservice.integration.WhatsAppNotificationClient;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.SupportPermissions;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared base for the customer-support integration tests: the real security
 * chain, the real schema (V54 applied by Flyway on the Testcontainers Postgres),
 * and mocked notification clients so no test ever reaches a gateway.
 *
 * <p>Every support test class extends this one so they share ONE Spring
 * context (the mock set is part of the context cache key). Each test seeds its
 * own customer under a fresh random phone, so tests never see each other's rows
 * and nothing needs cleaning up — which suits tables that are append-only.
 */
public abstract class SupportTestBase extends ControllerSecurityTestBase {

    protected static final List<String> AGENT_PERMS = List.of(
            SupportPermissions.READ, SupportPermissions.MANAGE, SupportPermissions.SEND_MESSAGES);
    protected static final List<String> SUPERVISOR_PERMS = List.of(
            SupportPermissions.READ, SupportPermissions.MANAGE, SupportPermissions.SUPERVISE,
            SupportPermissions.SEND_MESSAGES);

    @MockitoBean protected SmsNotificationClient sms;
    @MockitoBean protected WhatsAppNotificationClient whatsApp;

    @Autowired protected MerchantRepository merchantRepository;
    @Autowired protected LoyaltyUserRepository loyaltyUserRepository;
    @Autowired protected WalletRepository walletRepository;
    @Autowired protected LoyaltyTransactionRepository transactionRepository;
    @Autowired protected VoucherRepository voucherRepository;
    @Autowired protected VoucherPurchaseOrderRepository orderRepository;
    @Autowired protected DataSource dataSource;

    protected final ObjectMapper json = new ObjectMapper();
    protected JdbcTemplate jdbc;

    @BeforeEach
    void supportDefaults() {
        jdbc = new JdbcTemplate(dataSource);
        // Both channels provisioned unless a test says otherwise.
        when(sms.isConfigured()).thenReturn(true);
        when(whatsApp.isConfigured()).thenReturn(true);
    }

    // ---- Tokens ----

    protected String agentToken(UUID agentUuid) {
        return token(agentUuid, "SUPPORT_AGENT", AGENT_PERMS);
    }

    protected String supervisorToken(UUID agentUuid) {
        return token(agentUuid, "SUPPORT_SUPERVISOR", SUPERVISOR_PERMS);
    }

    protected String token(UUID agentUuid, String role, List<String> perms) {
        return TestJwtFactory.builder("agent-" + agentUuid.toString().substring(0, 8) + "@example.com")
                .role(role).userId(agentUuid).permissions(perms).sign(jwtSecret);
    }

    // ---- Seeding ----

    /** A random, VALID Zimbabwe mobile in E.164 — fresh per test, so rows never collide. */
    protected static String randomPhone() {
        return "+26377" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    /** The national spelling a till types: +263771234567 -> 0771234567. */
    protected static String national(String e164) {
        return "0" + e164.substring(4);
    }

    protected record Seeded(UUID tenantId, UUID merchantId, LoyaltyUser membership, Wallet wallet,
                            LoyaltyTransaction transaction, Voucher heldVoucher) {}

    /** One tenant, one merchant, a membership, a wallet with points, one earn and one held voucher. */
    protected Seeded seedCustomer(String phone) {
        UUID tenantId = newTenant("support");
        Merchant merchant = newMerchant(tenantId, "Example Pizza");
        LoyaltyUser member = newMembership(tenantId, phone, LoyaltyUser.Status.ACTIVE);
        Wallet wallet = new Wallet();
        wallet.setPhoneNumber(phone);
        wallet.setBalance(new BigDecimal("1250.0000"));
        wallet = walletRepository.save(wallet);
        LoyaltyTransaction txn = newTransaction(tenantId, merchant.getId(), member.getId(), new BigDecimal("250"));
        Voucher held = newVoucher(tenantId, merchant.getId(), phone, member.getId(), null);
        return new Seeded(tenantId, merchant.getId(), member, wallet, txn, held);
    }

    protected Merchant newMerchant(UUID tenantId, String name) {
        Merchant m = new Merchant();
        m.setTenantId(tenantId);
        m.setName(name + " " + UUID.randomUUID().toString().substring(0, 6));
        return merchantRepository.save(m);
    }

    protected LoyaltyUser newMembership(UUID tenantId, String phone, LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(tenantId);
        u.setPhoneNumber(phone);
        u.setStatus(status);
        return loyaltyUserRepository.save(u);
    }

    protected LoyaltyTransaction newTransaction(UUID tenantId, UUID merchantId, UUID userId, BigDecimal points) {
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTenantId(tenantId);
        t.setMerchantId(merchantId);
        t.setUserId(userId);
        t.setType(TransactionType.PURCHASE);
        t.setAmount(new BigDecimal("25.00"));
        t.setPointsDelta(points);
        t.setReference("POS-" + UUID.randomUUID().toString().substring(0, 12));
        return transactionRepository.save(t);
    }

    protected Voucher newVoucher(UUID tenantId, UUID merchantId, String assigneePhone, UUID assignedUserId,
                                 String senderPhone) {
        Voucher v = new Voucher();
        v.setTenantId(tenantId);
        v.setMerchantId(merchantId);
        v.setCode(randomCode());
        v.setSignature("test-signature");
        v.setAssigneePhone(assigneePhone);
        v.setAssignedUserId(assignedUserId);
        v.setAssigneeName("Rudo");
        v.setSenderPhone(senderPhone);
        v.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        v.setValue(new BigDecimal("5.00"));
        v.setCurrency("USD");
        v.setStatus(Voucher.Status.ISSUED);
        v.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return voucherRepository.save(v);
    }

    protected VoucherPurchaseOrder newOrder(UUID tenantId, UUID merchantId, String payerPhone, String assigneePhone) {
        VoucherPurchaseOrder o = new VoucherPurchaseOrder();
        o.setOrderRef("VCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        o.setTenantId(tenantId);
        o.setMerchantId(merchantId);
        o.setAmount(new BigDecimal("5.00"));
        o.setCurrency("USD");
        o.setPayerPhone(payerPhone);
        o.setAssigneePhone(assigneePhone);
        o.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        o.setUsageLimit(1);
        o.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        return orderRepository.save(o);
    }

    protected static String randomCode() {
        return "9" + String.format("%015d", ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L));
    }

    // ---- Calls ----

    /** Looks {@code phone} up as the agent and returns the lookupId. */
    protected UUID lookup(String token, String phone) throws Exception {
        MvcResult r = mockMvc.perform(post("/loyalty/support/customers/lookup")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andReturn();
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("lookup failed: " + r.getResponse().getStatus() + " "
                    + r.getResponse().getContentAsString());
        }
        return UUID.fromString(body(r).path("data").path("lookupId").asText());
    }

    protected JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** The activity rows one agent wrote, oldest first. */
    protected List<String> actionsOf(UUID agentUuid) {
        return jdbc.queryForList("SELECT action FROM support_activity WHERE agent_uuid = ? ORDER BY created_at, id",
                String.class, agentUuid.toString());
    }

    /**
     * Runs one statement against the append-only support tables with their
     * triggers bypassed — for a test that must age a row (a lookup past its
     * TTL, a message out of the rate-limit window). {@code session_replication_role
     * = replica} skips ordinary triggers for this one transaction only; the
     * application can do no such thing. Values are inlined: test fixtures only.
     */
    protected void maintenanceUpdate(String sql) {
        jdbc.execute("DO $$ BEGIN SET LOCAL session_replication_role = replica; " + sql + "; END $$");
    }
}
