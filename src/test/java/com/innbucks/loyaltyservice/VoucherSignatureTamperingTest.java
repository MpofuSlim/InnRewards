package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.FraudAttemptRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
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
    @Autowired VoucherService voucherService;
    @Autowired VoucherRepository voucherRepository;
    @Autowired FraudAttemptRepository fraudAttemptRepository;
    @Autowired com.innbucks.loyaltyservice.config.LoyaltyProperties loyaltyProperties;

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

        // Issue a legitimate voucher (directly since V45 — no template). Its
        // signature is computed correctly by VoucherService.createVoucher.
        var issued = issueAsSuperAdmin(t.getId(),
                new Dtos.IssueVoucherRequest(mr.id(), null, new BigDecimal("20"), "USD", null,
                        null, null, user.getId(),
                        Voucher.DeliveryChannel.NONE, null));

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

    /**
     * Pre-V45 vouchers were signed over {@code tenant:templateId:code};
     * template-less vouchers sign over {@code tenant:-:code}. Verification
     * recomputes from the STORED row, so a legacy row must keep verifying with
     * no re-signing pass — this is the back-compat the signPayload helper
     * exists for, and this test is what fails if someone "simplifies" the
     * payload to drop the stored template id.
     */
    @Test
    @Transactional
    void legacyTemplateSignedVoucherStillVerifies() {
        Tenant draft = new Tenant();
        draft.setCode("sig-legacy-" + System.nanoTime());
        draft.setName("Legacy Signature Test");
        final Tenant t = tenantRepository.save(draft);

        Dtos.MerchantResponse mr = merchantService.create(t.getId(),
                new Dtos.MerchantRequest("Legacy Cafe", "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null)));
        LoyaltyUser user = userService.findOrEnrol(t.getId(), "+263770088888", mr.id());

        // Hand-craft a row exactly as the pre-V45 issue path persisted it: a
        // template id on the row and a signature over tenant:templateId:code.
        java.util.UUID legacyTemplateId = java.util.UUID.randomUUID();
        String code = "LEGACY-" + System.nanoTime();
        var signer = new com.innbucks.loyaltyservice.security.CryptoSigner(
                loyaltyProperties.voucher().secret());
        Voucher legacy = new Voucher();
        legacy.setTenantId(t.getId());
        legacy.setMerchantId(mr.id());
        legacy.setTemplateId(legacyTemplateId);
        legacy.setCode(code);
        legacy.setSignature(signer.sign(t.getId() + ":" + legacyTemplateId + ":" + code));
        legacy.setAssignedUserId(user.getId());
        legacy.setAssigneePhone(user.getPhoneNumber());
        legacy.setValue(new BigDecimal("5.00"));
        legacy.setCurrency("USD");
        legacy.setUsesRemaining(1);
        voucherRepository.save(legacy);

        var redemption = voucherService.redeem(t.getId(), mr.id(),
                new Dtos.RedeemVoucherRequest(null, code, user.getId(),
                        "OUTLET-1", "device-legacy", "127.0.0.1"));
        assertThat(redemption.status()).isEqualTo(Voucher.Status.REDEEMED.name());
    }

    private Dtos.VoucherResponse issueAsSuperAdmin(java.util.UUID tenantId,
                                                   Dtos.IssueVoucherRequest req) {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "test-fixture", null, java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
        try {
            return voucherService.issue(tenantId, req);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }
}
