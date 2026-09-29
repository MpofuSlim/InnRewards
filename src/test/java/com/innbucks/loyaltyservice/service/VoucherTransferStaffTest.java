package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.FraudAttempt;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may move a CUSTOMER's voucher. Transfer mints a fresh code and sends it
 * to the recipient, so whoever may transfer a voucher may send its value to any
 * phone they like. A cashier (SHOP_USER) can see every customer's vouchers in
 * the tenant, which made transfer a way to send any of them to an accomplice.
 *
 * <p>Uses the real {@link MerchantAuthz} over mocked repositories, so the
 * merchant pin is exercised rather than assumed.
 */
class VoucherTransferStaffTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT = UUID.randomUUID();
    private static final String HOLDER_PHONE = "+263771111111";
    private static final String RECIPIENT_PHONE = "+263772222222";
    private static final String STAFF_PHONE = "+263773333333";

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);
    private final UserService userService = mock(UserService.class);
    private final NotificationGateway notifications = mock(NotificationGateway.class);
    private final MemberActivityNotifier memberNotifier = mock(MemberActivityNotifier.class);
    private final FraudService fraud = mock(FraudService.class);
    private VoucherService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret");

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        when(merchants.findById(MERCHANT)).thenReturn(Optional.of(m));

        service = new VoucherService(
                vouchers,
                mock(VoucherBatchRepository.class),
                mock(VoucherRedemptionRepository.class),
                mock(MerchantService.class),
                new MerchantAuthz(merchants, mock(ShopRepository.class)),
                new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
                mock(com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository.class),
                mock(LoyaltyUserRepository.class),
                userService,
                notifications,
                fraud,
                new LoyaltyMetrics(new SimpleMeterRegistry()),
                memberNotifier,
                props, usdOnlyFx(),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        when(vouchers.findByCode(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCashier_cannotMoveACustomersVoucher() {
        Voucher v = liveVoucher();
        String code = v.getCode();
        authenticate("ROLE_SHOP_USER", MERCHANT, STAFF_PHONE);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(), toPhone(RECIPIENT_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode())
                .isEqualTo("NOT_VOUCHER_OWNER");

        // Unmoved, code unchanged, nobody told anything, nobody enrolled.
        assertThat(v.getAssigneePhone()).isEqualTo(HOLDER_PHONE);
        assertThat(v.getCode()).isEqualTo(code);
        verifyNoInteractions(notifications, memberNotifier);
        verify(userService, org.mockito.Mockito.never()).findOrCreatePending(any(), anyString(), any());
    }

    @Test
    void aCashierWhoHoldsTheVoucher_mayStillTransferTheirOwn() {
        // A cashier is also a customer. Their own voucher is theirs to give.
        Voucher v = liveVoucher();
        v.setAssigneePhone(STAFF_PHONE);
        recipient(RECIPIENT_PHONE);
        authenticate("ROLE_SHOP_USER", MERCHANT, STAFF_PHONE);

        service.transfer(TENANT, v.getId(), toPhone(RECIPIENT_PHONE));

        assertThat(v.getAssigneePhone()).isEqualTo(RECIPIENT_PHONE);
    }

    @Test
    void anAdminOfAnotherMerchant_cannotMoveIt() {
        Voucher v = liveVoucher();
        authenticate("ROLE_SHOP_ADMIN", OTHER_MERCHANT, STAFF_PHONE);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(), toPhone(RECIPIENT_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode())
                .isEqualTo("NOT_MERCHANT_OWNER");
        assertThat(v.getAssigneePhone()).isEqualTo(HOLDER_PHONE);
    }

    @Test
    void anAdminOfTheVouchersMerchant_mayMoveItForTheCustomer() {
        Voucher v = liveVoucher();
        String code = v.getCode();
        recipient(RECIPIENT_PHONE);
        authenticate("ROLE_SHOP_ADMIN", MERCHANT, STAFF_PHONE);

        Dtos.VoucherResponse resp = service.transfer(TENANT, v.getId(), toPhone(RECIPIENT_PHONE));

        assertThat(v.getAssigneePhone()).isEqualTo(RECIPIENT_PHONE);
        assertThat(v.getCode()).isNotEqualTo(code);
        assertThat(resp.code()).as("the acting admin never sees the rotated code").isNull();
        verify(notifications).deliver(v, RECIPIENT_PHONE);
    }

    @Test
    void anAdmin_cannotMoveACustomersVoucherToTheirOwnPhone() {
        Voucher v = liveVoucher();
        String code = v.getCode();
        recipient(STAFF_PHONE);
        authenticate("ROLE_SHOP_ADMIN", MERCHANT, STAFF_PHONE);

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(), toPhone(STAFF_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode())
                .isEqualTo("STAFF_RECIPIENT");

        assertThat(v.getAssigneePhone()).isEqualTo(HOLDER_PHONE);
        assertThat(v.getCode()).isEqualTo(code);
        verifyNoInteractions(notifications, memberNotifier);
        verify(fraud).record(eq(TENANT), any(), eq(MERCHANT), any(),
                eq(FraudAttempt.Reason.STAFF_RECIPIENT), anyString(), any(), any());
    }

    @Test
    void theStaffRecipientRefusal_standsEvenIfTheEvidenceRowCannotBeWritten() {
        Voucher v = liveVoucher();
        recipient(STAFF_PHONE);
        authenticate("ROLE_SHOP_ADMIN", MERCHANT, STAFF_PHONE);
        when(fraud.record(any(), any(), any(), any(), any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.transfer(TENANT, v.getId(), toPhone(STAFF_PHONE)))
                .isInstanceOf(LoyaltyException.class)
                .extracting(e -> ((LoyaltyException) e).getCode())
                .isEqualTo("STAFF_RECIPIENT");
    }

    // ---- fixtures ----

    private Voucher liveVoucher() {
        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(TENANT);
        v.setMerchantId(MERCHANT);
        v.setCode("4829137605128368");
        v.setSignature("sig");
        v.setStatus(Voucher.Status.ISSUED);
        v.setAssignedUserId(UUID.randomUUID());
        v.setAssigneePhone(HOLDER_PHONE);
        v.setUsesRemaining(1);
        v.setIssuedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        v.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        v.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        v.setValue(new BigDecimal("10.0000"));
        v.setCurrency("USD");
        when(vouchers.lockById(v.getId())).thenReturn(Optional.of(v));
        return v;
    }

    private void recipient(String phone) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(UUID.randomUUID());
        u.setPhoneNumber(phone);
        when(userService.findOrCreatePending(eq(TENANT), eq(phone), any())).thenReturn(u);
    }

    private static Dtos.VoucherTransferRequest toPhone(String phone) {
        return new Dtos.VoucherTransferRequest(null, phone, null);
    }

    private static void authenticate(String role, UUID merchantId, String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                "staff@test.local", null, List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(new CallerDetails(merchantId, null, phone, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static ExchangeRateService usdOnlyFx() {
        return new ExchangeRateService(
                mock(com.innbucks.loyaltyservice.repository.ExchangeRateRepository.class),
                new com.innbucks.loyaltyservice.config.SupportedCurrencies("USD", "USD"),
                new BigDecimal("25"));
    }
}
