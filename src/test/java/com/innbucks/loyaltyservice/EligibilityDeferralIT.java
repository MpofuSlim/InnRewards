package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.InnbucksCustomerValidateClient;
import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.EarnChannel;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.OnDemandEligibilityCheck;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.VoucherGuessGuard;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.WalletService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.MerchantFixtures;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V44 on-demand eligibility check runs OUTSIDE the spend transaction —
 * real controllers, real transactions, real Postgres.
 *
 * <p>The InnBucks directory client is a mock whose answer records what the
 * world looked like while it was being asked: whether the calling thread had a
 * transaction open, and — on the voucher path — whether ANOTHER connection
 * could take the voucher's row lock ({@code FOR UPDATE NOWAIT}). Before the
 * deferral the answers were "yes" and "no": the call was made inside the
 * spend's transaction, under the voucher's {@code PESSIMISTIC_WRITE} lock.
 */
class EligibilityDeferralIT extends ControllerSecurityTestBase {

    /** Switches the on-demand check on and off per test (see {@link OnDemandConfig}). */
    static final AtomicBoolean ON_DEMAND_ENABLED = new AtomicBoolean(true);

    /**
     * A real {@link OnDemandEligibilityCheck} — the class under the gate, throttle
     * and metrics included — with a Redis throttle that always grants the claim
     * (the test profile has no Redis, and without one the real class skips the
     * check entirely, by design). Its on/off switch reads
     * {@link #ON_DEMAND_ENABLED} exactly where the real class reads its
     * {@code enabled} flag.
     */
    @TestConfiguration
    static class OnDemandConfig {
        @Bean
        @Primary
        OnDemandEligibilityCheck testOnDemandEligibilityCheck(InnbucksCustomerValidateClient client,
                                                              LoyaltyMetrics metrics) {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.opsForValue()).thenReturn(ops);
            @SuppressWarnings("unchecked")
            ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(redis);
            return new OnDemandEligibilityCheck(client, metrics, provider, true, 900, 60) {
                @Override
                public boolean isActive() {
                    return ON_DEMAND_ENABLED.get() && super.isActive();
                }

                @Override
                public boolean confirmsCustomer(String e164Phone) {
                    return ON_DEMAND_ENABLED.get() && super.confirmsCustomer(e164Phone);
                }
            };
        }
    }

    @MockitoBean InnbucksCustomerValidateClient validateClient;
    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;
    @MockitoSpyBean VoucherGuessGuard guessGuard;

    @Autowired MerchantService merchantService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired VoucherService voucherService;
    @Autowired WalletService walletService;
    @Autowired LoyaltyUserRepository loyaltyUsers;
    @Autowired PhoneRegistrationRepository registrations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

    private UUID tenantId;
    private UUID merchantId;
    private String phone;
    private UUID userId;

    /** What the directory saw, one entry per call. */
    private final List<String> directoryCalls = new CopyOnWriteArrayList<>();
    /** Set by a voucher test: the row whose lock must be free while the directory is asked. */
    private final AtomicReference<UUID> voucherUnderTest = new AtomicReference<>();

    @BeforeEach
    void seed() {
        ON_DEMAND_ENABLED.set(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        tenantId = newTenant("elig-defer");
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("Deferral Cafe " + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        ruleAdminService.createRule(tenantId, merchantId, new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        phone = "+26377" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        // An earn to an unproven phone mints a PENDING projection holding 1000 points.
        transactionService.post(tenantId, merchantId,
                new Dtos.TransactionRequest(merchantId, null, phone, TransactionType.PURCHASE,
                        new BigDecimal("1000"), "USD", "SEED-" + UUID.randomUUID()),
                EarnChannel.CHECKOUT_S2S);
        LoyaltyUser u = loyaltyUsers.findByTenantIdAndPhoneNumber(tenantId, phone).orElseThrow();
        assertThat(u.getStatus()).isEqualTo(LoyaltyUser.Status.PENDING);
        userId = u.getId();

        when(validateClient.isConfigured()).thenReturn(true);
        directoryAnswers(() -> new InnbucksCustomerValidateClient.Customer("00"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        ON_DEMAND_ENABLED.set(true);
    }

    /** Every call records "tx=<active>" (and "lockFree=<bool>" on a voucher test), then answers. */
    private void directoryAnswers(Supplier<InnbucksCustomerValidateClient.CustomerCheckOutcome> answer) {
        // doAnswer, not when(...): re-stubbing through when() would CALL the
        // previous answer and record a directory call that never happened.
        doAnswer(inv -> {
            StringBuilder seen = new StringBuilder("tx=")
                    .append(TransactionSynchronizationManager.isActualTransactionActive());
            UUID voucherId = voucherUnderTest.get();
            if (voucherId != null) {
                seen.append(" lockFree=").append(voucherRowLockIsFree(voucherId));
            }
            directoryCalls.add(seen.toString());
            return answer.get();
        }).when(validateClient).checkCustomer(anyString());
    }

    /** Another connection, another thread: can it take the voucher's row lock right now? */
    private boolean voucherRowLockIsFree(UUID voucherId) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                jdbc.queryForList("SELECT id FROM vouchers WHERE id = ? FOR UPDATE NOWAIT", voucherId);
                return true;
            } catch (DataAccessException lockedOrFailed) {
                return false;
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private String customerToken(String phoneClaim) {
        return TestJwtFactory.builder("customer-" + UUID.randomUUID() + "@test.local")
                .role("CUSTOMER").tier(1).verified(true).phoneNumber(phoneClaim).sign(jwtSecret);
    }

    private org.springframework.test.web.servlet.ResultActions redeemPoints(String points) throws Exception {
        return mockMvc.perform(post("/loyalty/redeem")
                .header("Authorization", bearer(customerToken(phone)))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantId":"%s","userId":"%s","points":%s,"reason":"it"}
                        """.formatted(merchantId, userId, points)));
    }

    private Dtos.VoucherResponse issueVoucherToPhone() {
        return voucherService.issue(tenantId, new Dtos.IssueVoucherRequest(
                merchantId, null, new BigDecimal("5"), "USD", null,
                phone, null, null, null, null, Voucher.DeliveryChannel.NONE, null));
    }

    private org.springframework.test.web.servlet.ResultActions redeemVoucher(String token, String code)
            throws Exception {
        return mockMvc.perform(post("/loyalty/vouchers/redeem")
                .header("Authorization", bearer(token))
                .header("X-Tenant-Id", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"merchantId":"%s","code":"%s"}
                        """.formatted(merchantId, code)));
    }

    private PhoneRegistration registration() {
        return registrations.findById(phone).orElse(null);
    }

    private double guardFailures() {
        return meters.find("loyalty.voucher.guard.failures").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    // ---- the three spend gates, confirmed customer: asked once, outside any transaction ----

    @Test
    void pointsRedeem_confirmedCustomer_isAskedOutsideTheTransaction_registered_andSpentOnTheReplay()
            throws Exception {
        redeemPoints("100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newBalance").value(900));

        assertThat(directoryCalls).containsExactly("tx=false");
        PhoneRegistration reg = registration();
        assertThat(reg).isNotNull();
        assertThat(reg.getSource()).isEqualTo(PhoneRegistration.Source.INNBUCKS_VALIDATE);
        assertThat(reg.getSourceRef()).isEqualTo("on-demand-spend");
        assertThat(loyaltyUsers.findById(userId).orElseThrow().getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
    }

    @Test
    void transfer_confirmedCustomer_isAskedOutsideTheTransaction_andSentOnTheReplay() throws Exception {
        mockMvc.perform(post("/loyalty/transfer")
                        .header("Authorization", bearer(customerToken(phone)))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromUserId":"%s","toPhone":"+263779990001","points":40,"reason":"gift"}
                                """.formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newSenderBalance").value(960));

        assertThat(directoryCalls).containsExactly("tx=false");
        assertThat(registration()).isNotNull();
    }

    @Test
    void voucherRedeem_confirmedHolder_isAskedWithTheRowLockReleased_inOneGuardedAttempt() throws Exception {
        Dtos.VoucherResponse v = issueVoucherToPhone();
        voucherUnderTest.set(v.id());
        double failuresBefore = guardFailures();
        org.mockito.Mockito.clearInvocations(guessGuard);

        redeemVoucher(customerToken(phone), v.code())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REDEEMED"));

        // No transaction on the thread, and another connection could lock the
        // voucher row NOWAIT: the PESSIMISTIC_WRITE lock was released first.
        assertThat(directoryCalls).containsExactly("tx=false lockFree=true");
        // The deferral and replay happened inside ONE reserved attempt, and the
        // deferral was not counted as a miss.
        verify(guessGuard, times(1)).attempt(any(Supplier.class));
        assertThat(guardFailures()).isEqualTo(failuresBefore);
        assertThat(registration()).isNotNull();
        // A deferral writes no REJECTED redemption row (the rollback leaves nothing).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM voucher_redemptions WHERE voucher_id = ? AND result = 'REJECTED'",
                Long.class, v.id())).isZero();
    }

    // ---- not confirmed / revoked / disabled: refused USER_PENDING, never looped ----

    @Test
    void notACustomer_isRefusedUserPending_afterExactlyOneDirectoryCall() throws Exception {
        directoryAnswers(() -> new InnbucksCustomerValidateClient.NotACustomer("unknown msisdn"));

        redeemPoints("100")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_PENDING"));

        assertThat(directoryCalls).containsExactly("tx=false");
        assertThat(registration()).isNull();
        assertThat(walletService.mainWallet(phone).getBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void aRevokedRegistration_isNotReinstated_andNotLooped() throws Exception {
        PhoneRegistration revoked = new PhoneRegistration();
        revoked.setPhoneNumber(phone);
        revoked.setSource(PhoneRegistration.Source.INNBUCKS_VALIDATE);
        revoked.setRevokedAt(Instant.now());
        revoked.setRevokedReason("operator batch revoke");
        registrations.save(revoked);

        redeemPoints("100")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_PENDING"));

        assertThat(directoryCalls).containsExactly("tx=false");
        assertThat(registration().getRevokedAt()).isNotNull();
        assertThat(loyaltyUsers.findById(userId).orElseThrow().getStatus()).isEqualTo(LoyaltyUser.Status.PENDING);
    }

    @Test
    void onDemandDisabled_neverAsks_andRefusesExactlyAsBefore() throws Exception {
        ON_DEMAND_ENABLED.set(false);

        redeemPoints("100")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_PENDING"))
                .andExpect(jsonPath("$.message").value("Your rewards account is still being set up, so these "
                        + "points can't be spent yet. You'll keep earning in the meantime."));

        verify(validateClient, never()).checkCustomer(anyString());
        assertThat(registration()).isNull();
    }

    @Test
    void theRegistrationCommits_evenWhenTheReplayedSpendFails() throws Exception {
        redeemPoints("5000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(directoryCalls).containsExactly("tx=false");
        // Owner-accepted: the eligibility registration is its own commit.
        assertThat(registration()).isNotNull();
        assertThat(registration().getRevokedAt()).isNull();
        assertThat(loyaltyUsers.findById(userId).orElseThrow().getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);
        assertThat(walletService.mainWallet(phone).getBalance()).isEqualByComparingTo("1000");
    }

    // ---- ordering: a caller with no right to the account never reaches the gate ----

    @Test
    void transfer_fromSomeoneElsesAccount_isRefusedNotWalletOwner_beforeTheGate() throws Exception {
        mockMvc.perform(post("/loyalty/transfer")
                        .header("Authorization", bearer(customerToken("+263779990002")))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromUserId":"%s","toPhone":"+263779990003","points":40}
                                """.formatted(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_WALLET_OWNER"));

        verify(validateClient, never()).checkCustomer(anyString());
        assertThat(registration()).isNull();
    }

    @Test
    void voucherRedeem_byStaffOfAnotherMerchant_isRefusedNotMerchantOwner_beforeTheGate() throws Exception {
        Dtos.VoucherResponse v = issueVoucherToPhone();
        String email = "other-admin-" + UUID.randomUUID() + "@test.local";
        joinTenant(tenantId, email);
        // A MERCHANT_ADMIN of an organization that does not own this merchant.
        String token = TestJwtFactory.merchantAdmin(email, jwtSecret);

        redeemVoucher(token, v.code())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MERCHANT_OWNER"));

        verify(validateClient, never()).checkCustomer(anyString());
        assertThat(registration()).isNull();
    }
}
