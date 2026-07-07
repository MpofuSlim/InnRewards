package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.QrToken;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.InvoicingService;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.QrService;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
import com.innbucks.loyaltyservice.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class LoyaltyServiceIntegrationTest {

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired TransactionService transactionService;
    @Autowired TransferService transferService;
    @Autowired WalletService walletService;
    @Autowired VoucherTemplateService voucherTemplateService;
    @Autowired VoucherService voucherService;
    @Autowired QrService qrService;
    @Autowired InvoicingService invoicingService;

    // Stubbed so enrolment doesn't require a running user-service. Returns a
    // tier-1 customer for any phone number passed in.
    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    @Transactional
    void earnsPointsAndRedeemsVoucher() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Cafe Westgate", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        LoyaltyUser u = userService.findOrEnrol(t.getId(), "+263770000001", mr.id());

        var txn = transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, u.getId(), null, TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "ref-1"));
        assertThat(txn.pointsDelta()).isEqualByComparingTo("100");
        assertThat(txn.balanceAfter()).isEqualByComparingTo("100");

        // Issue + redeem voucher
        VoucherTemplate tpl = voucherTemplateService.create(t.getId(), mr.id(),
                new Dtos.VoucherTemplateRequest(null, "10% off",
                        VoucherTemplate.VoucherType.SINGLE_USE,
                        VoucherTemplate.ValueType.PERCENT,
                        "USD", null, 1, 30, null));

        var v = voucherService.issue(t.getId(),
                new Dtos.IssueVoucherRequest(null, tpl.getId(), new BigDecimal("10"),
                        null, null, u.getId(),
                        Voucher.DeliveryChannel.NONE, null, null, null));

        var redemption = voucherService.redeem(t.getId(), mr.id(),
                new Dtos.RedeemVoucherRequest(null, v.code(), u.getId(),
                        "OUTLET-1", "device-A", "127.0.0.1"));
        assertThat(redemption.status()).isEqualTo(Voucher.Status.REDEEMED.name());

        // Duplicate redemption attempt is rejected
        assertThatThrownBy(() ->
                voucherService.redeem(t.getId(), mr.id(),
                        new Dtos.RedeemVoucherRequest(null, v.code(), u.getId(),
                                "OUTLET-1", "device-A", "127.0.0.1")))
                .isInstanceOf(LoyaltyException.class);
    }

    @Test
    @Transactional
    void transferDeductsAndCreditsAtomically() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Mall Bulawayo", "Retail", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, BigDecimal.ZERO, null), new Dtos.FeeModel(Merchant.FeeType.FIXED, BigDecimal.ZERO, null)));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser alice = userService.findOrEnrol(t.getId(), "+263770000010", mr.id());
        LoyaltyUser bob = userService.findOrEnrol(t.getId(), "+263770000011", mr.id());

        transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, alice.getId(), null, TransactionType.PURCHASE,
                        new BigDecimal("50"), "USD", "ref-trans-1"));

        // TransferService verifies the caller owns the sender wallet (Phase 5
        // P2P authz fix). When invoked directly from this test there is no
        // HTTP security context, so we install one matching what JwtFilter
        // would set on a real CUSTOMER request from Alice.
        withCaller(alice.getPhoneNumber(), "CUSTOMER", () ->
                transferService.transfer(t.getId(),
                        new Dtos.TransferRequest(alice.getId(), bob.getId(), null,
                                new BigDecimal("20"), "gift")));

        assertThat(walletService.totalBalance(alice.getPhoneNumber())).isEqualByComparingTo("30");
        assertThat(walletService.totalBalance(bob.getPhoneNumber())).isEqualByComparingTo("20");
    }

    @Test
    @Transactional
    void qrConsumeAwardsPointsAndIsSingleUse() {
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Pump Mutare", "Fuel", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, BigDecimal.ZERO, null), new Dtos.FeeModel(Merchant.FeeType.FIXED, BigDecimal.ZERO, null)));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.QR_PAY,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        LoyaltyUser u = userService.findOrEnrol(t.getId(), "+263770000020", mr.id());

        // A MERCHANT-sourced (points-awarding) QR may only be minted by staff who
        // administer the merchant — a SUPER_ADMIN operator here. Issuing without an
        // authorized caller is now (correctly) rejected as NOT_MERCHANT_OWNER.
        var qrHolder = new Dtos.QrPayload[1];
        withCaller("ops@platform.test", "SUPER_ADMIN", () ->
                qrHolder[0] = qrService.issue(t.getId(),
                        new Dtos.QrIssueRequest(QrToken.SourceType.MERCHANT, mr.id(),
                                TransactionType.QR_PAY, new BigDecimal("25"), "USD", 60)));
        var qr = qrHolder[0];

        // The customer scans and consumes for THEIR OWN account — consume binds the
        // credited userId to the authenticated caller.
        var txnHolder = new Dtos.TransactionResponse[1];
        withCaller(u.getPhoneNumber(), "CUSTOMER", () ->
                txnHolder[0] = qrService.consume(t.getId(),
                        new Dtos.QrConsumeRequest(qr.token(), qr.signature(), u.getId(), "ref-qr-1")));
        assertThat(txnHolder[0].pointsDelta()).isEqualByComparingTo("25");

        // Reuse must fail (single-use), still as the legitimate owner so the
        // rejection is on the consumed flag — not authorization.
        withCaller(u.getPhoneNumber(), "CUSTOMER", () ->
                assertThatThrownBy(() -> qrService.consume(t.getId(),
                        new Dtos.QrConsumeRequest(qr.token(), qr.signature(), u.getId(), "ref-qr-2")))
                        .isInstanceOf(LoyaltyException.class));
    }

    @Test
    @Transactional
    void invoicingSkipsZeroBillingPeriods() {
        // After fee-per-point was removed (V11), a period with no voucher
        // activity bills to zero. The service should NOT persist a $0.00
        // invoice — the merchant's billing page would otherwise fill with
        // phantom rows for every quiet month. Verified end-to-end: post a
        // points-earning transaction (no fee on points), then call
        // generate() and expect empty.
        Tenant t = saveTenant();
        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Pharmacy Gweru", "Health", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("1.00"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.50"), null)));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));
        LoyaltyUser u = userService.findOrEnrol(t.getId(), "+263770000030", mr.id());

        transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, u.getId(), null, TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "ref-inv-1"));

        var merchant = merchantService.requireMerchant(t.getId(), mr.id());
        var inv = invoicingService.generate(merchant,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(inv).isEmpty();
    }

    private Tenant saveTenant() {
        Tenant t = new Tenant();
        t.setCode("ACME-" + UUID.randomUUID().toString().substring(0, 8));
        t.setName("Acme");
        t.setCreatedAt(Instant.now());
        return tenantRepository.save(t);
    }

    /**
     * Installs a SecurityContext carrying the given phone + role for the
     * duration of the lambda — emulating what JwtFilter would set on a real
     * authenticated HTTP request. Used by tests that drive services directly
     * (no MockMvc) but still need to satisfy auth checks like the P2P transfer
     * ownership guard.
     */
    private void withCaller(String phoneNumber, String role, Runnable body) {
        var authorities = java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                phoneNumber, null, authorities);
        auth.setDetails(new com.innbucks.loyaltyservice.security.CallerDetails(null, null, phoneNumber, null));
        var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
        try {
            body.run();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }
}
