package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.service.VoucherTemplateService;
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
 * Proves the voucher HMAC signature actually gates redemption. If someone
 * tampered with the {@code signature} column in the DB directly (e.g. by
 * SQL injection further upstream, a compromised DBA account, or a buggy
 * migration), redemption must reject the voucher with {@code BAD_SIGNATURE}
 * and record a fraud attempt — the signature is the last line of defence.
 *
 * <p>Runs on H2 (the test profile) — the signature check is pure Java logic
 * inside VoucherService.redeem so Postgres-specific behaviour doesn't matter
 * here.
 */
@SpringBootTest
@ActiveProfiles("test")
class VoucherSignatureTamperingTest {

    @Autowired TenantRepository tenantRepository;
    @Autowired MerchantService merchantService;
    @Autowired UserService userService;
    @Autowired VoucherTemplateService voucherTemplateService;
    @Autowired VoucherService voucherService;
    @Autowired VoucherRepository voucherRepository;
    @Autowired FraudAttemptRepository fraudAttemptRepository;

    @MockitoBean UserServiceClient userServiceClient;

    @BeforeEach
    void stubUserServiceLookup() {
        when(userServiceClient.getCustomerTier(anyString()))
                .thenAnswer(inv -> Optional.of(
                        new CustomerTierResponseDTO(inv.getArgument(0), 1, 2)));
    }

    @Test
    @Transactional
    void tamperedSignatureIsRejectedAndRecordedAsFraud() {
        Tenant draft = new Tenant();
        draft.setCode("sig-tamper-" + System.nanoTime());
        draft.setName("Signature Tamper Test");
        final Tenant t = tenantRepository.save(draft);

        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Tamper Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null), new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));

        LoyaltyUser user = userService.findOrEnrol(t.getId(), "+263770099999", mr.id());

        VoucherTemplate tpl = voucherTemplateService.create(t.getId(), mr.id(),
                new Dtos.VoucherTemplateRequest(null, "20% off",
                        VoucherTemplate.VoucherType.SINGLE_USE,
                        VoucherTemplate.ValueType.PERCENT,
                        "USD", null, 1, 30, null));

        // Issue a legitimate voucher. Its signature is computed correctly by
        // VoucherService.createFromTemplate.
        var issued = voucherService.issue(t.getId(),
                new Dtos.IssueVoucherRequest(null, tpl.getId(), new BigDecimal("20"),
                        null, null, user.getId(),
                        Voucher.DeliveryChannel.NONE, null, null, null));

        // Now reach into the DB and overwrite the signature with garbage —
        // simulating a malicious mutation through any non-application path.
        Voucher persisted = voucherRepository.findByCode(issued.code()).orElseThrow();
        long fraudCountBefore = fraudAttemptRepository.count();
        persisted.setSignature("tampered-signature-not-the-real-hmac");
        voucherRepository.save(persisted);

        // Redemption must reject — the HMAC re-check inside VoucherService.redeem
        // fails before any of the other downstream checks (expiry, uses, etc.).
        assertThatThrownBy(() ->
                voucherService.redeem(t.getId(), mr.id(),
                        new Dtos.RedeemVoucherRequest(null, issued.code(), user.getId(),
                                "OUTLET-1", "device-tamper", "127.0.0.1")))
                .isInstanceOf(LoyaltyException.class)
                .hasMessageContaining("signature");

        // And a fraud attempt row was recorded so the operator can spot the pattern.
        assertThat(fraudAttemptRepository.count()).isGreaterThan(fraudCountBefore);
        FraudAttempt last = fraudAttemptRepository.findAll().stream()
                .reduce((a, b) -> b)  // last
                .orElseThrow();
        assertThat(last.getReason()).isEqualTo(FraudAttempt.Reason.BAD_SIGNATURE);
        assertThat(last.getVoucherCode()).isEqualTo(issued.code());
    }
}
