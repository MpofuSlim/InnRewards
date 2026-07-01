package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.RedemptionService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end: a merchant credits points to an unregistered phone, an admin
 * issues that phone a voucher, the recipient registers (promote webhook
 * fires), and only then can they spend.
 *
 * <p>Runs on the H2 test profile — the logic is pure Java orchestration on
 * top of standard JPA, no Postgres-specific behaviour involved.
 */
@SpringBootTest
@ActiveProfiles("test")
class PhoneKeyedWalletTest {

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired RuleAdminService ruleAdminService;
    @Autowired UserService userService;
    @Autowired TransactionService transactionService;
    @Autowired RedemptionService redemptionService;
    @Autowired VoucherTemplateService voucherTemplateService;
    @Autowired VoucherService voucherService;
    @Autowired WalletService walletService;
    @Autowired LoyaltyUserRepository users;
    @Autowired TransferService transferService;

    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    @Transactional
    void accruePending_thenPromote_thenSpend() {
        Tenant draft = new Tenant();
        draft.setCode("pkw-" + System.nanoTime());
        draft.setName("Phone-Keyed Wallet Test");
        final Tenant t = tenantRepository.save(draft);

        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Cafe Pending", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null), new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));

        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        String phone = "+263770099900";

        // 1) Sender posts a $100 PURCHASE to a phone that's never been seen.
        var txn = transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, null, phone, TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "pkw-ref-1"));
        assertThat(txn.pointsDelta()).isEqualByComparingTo("100");
        assertThat(txn.balanceAfter()).isEqualByComparingTo("100");

        // A PENDING LoyaltyUser exists with the credited balance.
        LoyaltyUser created = users.findByTenantIdAndPhoneNumber(t.getId(), phone).orElseThrow();
        assertThat(created.getStatus()).isEqualTo(LoyaltyUser.Status.PENDING);
        assertThat(walletService.totalBalance(created.getPhoneNumber())).isEqualByComparingTo("100");

        // 2) Admin issues a voucher to the same phone — the same PENDING user
        // is reused (no duplicate row).
        VoucherTemplate tpl = voucherTemplateService.create(t.getId(), mr.id(),
                new Dtos.VoucherTemplateRequest(null, "Welcome 10",
                        VoucherTemplate.VoucherType.SINGLE_USE,
                        VoucherTemplate.ValueType.PERCENT,
                        "USD", null, 1, 30, null));
        var voucher = voucherService.issue(t.getId(),
                new Dtos.IssueVoucherRequest(null, tpl.getId(), new BigDecimal("10"),
                        phone, "Pending Pat", null,
                        Voucher.DeliveryChannel.NONE, null, null, null));
        assertThat(voucher.code()).isNotBlank();
        // The voucher response snapshots its template's value (V7 migration).
        // Editing the template later must NOT change what's already issued, so
        // these three fields are stored on the Voucher row, not looked up live.
        assertThat(voucher.valueType()).isEqualTo("PERCENT");
        assertThat(voucher.value()).isEqualByComparingTo("10");
        assertThat(voucher.currency()).isEqualTo("USD");

        // 3) PENDING user cannot redeem the voucher yet.
        assertThatThrownBy(() ->
                voucherService.redeem(t.getId(), mr.id(),
                        new Dtos.RedeemVoucherRequest(null, voucher.code(), created.getId(),
                                "OUTLET-A", "dev-1", "127.0.0.1")))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("unregistered");

        // 4) Promote webhook fires. PENDING -> ACTIVE.
        int promoted = userService.promoteByPhone(phone);
        assertThat(promoted).isEqualTo(1);
        LoyaltyUser activated = users.findById(created.getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(LoyaltyUser.Status.ACTIVE);

        // 5) Now the customer can redeem points (their accrued $100 -> 100 points).
        BigDecimal newBalance = redemptionService.redeemPoints(t.getId(), mr.id(),
                new Dtos.RedemptionRequest(null, created.getId(),
                        new BigDecimal("40"), "Coffee on the house", null)).balance();
        assertThat(newBalance).isEqualByComparingTo("60");

        // 6) And the voucher redemption now succeeds.
        var redemption = voucherService.redeem(t.getId(), mr.id(),
                new Dtos.RedeemVoucherRequest(null, voucher.code(), created.getId(),
                        "OUTLET-A", "dev-1", "127.0.0.1"));
        assertThat(redemption.status()).isEqualTo(Voucher.Status.REDEEMED.name());

        // Replays of the promote webhook are no-ops (idempotency).
        assertThat(userService.promoteByPhone(phone)).isZero();
    }

    @Test
    @Transactional
    void transfer_rejectsCustomerSpendingSomeoneElsesWallet() {
        Tenant draft = new Tenant();
        draft.setCode("pkw-authz-" + System.nanoTime());
        draft.setName("P2P authz");
        final Tenant t = tenantRepository.save(draft);

        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("AuthzCafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, BigDecimal.ZERO, null), new Dtos.FeeModel(Merchant.FeeType.FIXED, BigDecimal.ZERO, null)));
        ruleAdminService.createRule(t.getId(), mr.id(),
                new Dtos.RuleRequest(null, TransactionType.PURCHASE,
                        BigDecimal.ONE, BigDecimal.ONE, null, null, null, null));

        LoyaltyUser alice = userService.findOrEnrol(t.getId(), "+263770070001", mr.id());
        LoyaltyUser bob = userService.findOrEnrol(t.getId(), "+263770070002", mr.id());
        // Seed Alice with some balance so the would-be transfer has something to steal.
        transactionService.post(t.getId(), mr.id(),
                new Dtos.TransactionRequest(null, alice.getId(), null, TransactionType.PURCHASE,
                        new BigDecimal("100"), "USD", "pkw-authz-seed"));

        // Bob (CUSTOMER) tries to transfer FROM Alice's wallet. Must be rejected.
        assertThatThrownBy(() ->
                withSecurityContext(bob.getPhoneNumber(), "CUSTOMER", () ->
                        transferService.transfer(t.getId(),
                                new Dtos.TransferRequest(alice.getId(), bob.getId(), null,
                                        new BigDecimal("10"), "theft"))))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("own loyalty account");

        // Alice herself can still transfer (control case).
        withSecurityContext(alice.getPhoneNumber(), "CUSTOMER", () ->
                transferService.transfer(t.getId(),
                        new Dtos.TransferRequest(alice.getId(), bob.getId(), null,
                                new BigDecimal("10"), "legit")));
        assertThat(walletService.totalBalance(bob.getPhoneNumber())).isEqualByComparingTo("10");
    }

    private void withSecurityContext(String phoneNumber, String role, Runnable body) {
        var authorities = java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                phoneNumber, null, authorities);
        auth.setDetails(new CallerDetails(null, null, phoneNumber, null));
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
