package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.ExchangeRateRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherBatchRepository;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the voucher sender identity (V46): who the voucher is FROM is stamped
 * at issue, surfaces on the response, personalises the recipient's message
 * data, and earns the sender their own confirmation copy — with the
 * boundaries that matter:
 *
 * <ul>
 *   <li>senderPhone defaults to the issuing caller's own JWT phone;</li>
 *   <li>no copy when sender and recipient are the same phone;</li>
 *   <li>bulk stock never carries a sender (and never messages one);</li>
 *   <li>the sender name is HTML-stripped like the assignee's.</li>
 * </ul>
 */
class VoucherSenderIdentityTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String SENDER_PHONE = "+263782608767";
    private static final String RECIPIENT_PHONE = "+263786546765";

    private static final SupportedCurrencies CURRENCIES =
            new SupportedCurrencies("USD", "USD");

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final UserService userService = mock(UserService.class);
    private final NotificationGateway notifications = mock(NotificationGateway.class);

    private VoucherService service;

    @BeforeEach
    void setUp() {
        LoyaltyProperties props = mock(LoyaltyProperties.class, RETURNS_DEEP_STUBS);
        when(props.voucher().secret()).thenReturn("test-voucher-secret-value");
        when(props.voucher().defaultValidityDays()).thenReturn(365);
        service = new VoucherService(vouchers, mock(VoucherBatchRepository.class),
                mock(VoucherRedemptionRepository.class),
                mock(MerchantService.class), merchantAuthz, CURRENCIES, rules,
                mock(LoyaltyUserRepository.class),
                userService, notifications,
                mock(FraudService.class), new LoyaltyMetrics(new SimpleMeterRegistry()),
                mock(com.innbucks.loyaltyservice.integration.MemberActivityNotifier.class),
                props, new ExchangeRateService(mock(ExchangeRateRepository.class),
                        CURRENCIES, new BigDecimal("25")));
        when(vouchers.findByCode(anyString())).thenReturn(Optional.empty());
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of());

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Test Merchant");
        m.setCurrency("USD");
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenReturn(m);

        LoyaltyUser recipient = new LoyaltyUser();
        recipient.setId(UUID.randomUUID());
        recipient.setTenantId(TENANT);
        recipient.setPhoneNumber(RECIPIENT_PHONE);
        when(userService.findOrCreatePending(eq(TENANT), anyString(), eq(MERCHANT)))
                .thenReturn(recipient);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithPhone(String phone) {
        var auth = new UsernamePasswordAuthenticationToken("admin@example.com", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new CallerDetails(null, null, phone, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static Dtos.IssueVoucherRequest request(String senderName, String senderPhone,
                                                    String assigneePhone) {
        return new Dtos.IssueVoucherRequest(MERCHANT, null, new BigDecimal("5.00"), "USD", null,
                assigneePhone, "Sedrick Nyanyiwa", null,
                senderName, senderPhone, Voucher.DeliveryChannel.WHATSAPP, null);
    }

    private Voucher saved() {
        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void senderIdentity_isStampedAndReturned_andTheSenderGetsACopy() {
        Dtos.VoucherResponse resp = service.issue(TENANT,
                request("Tawanda Mpofu", SENDER_PHONE, RECIPIENT_PHONE));

        Voucher v = saved();
        assertThat(v.getSenderName()).isEqualTo("Tawanda Mpofu");
        assertThat(v.getSenderPhone()).isEqualTo(SENDER_PHONE);
        assertThat(resp.senderName()).isEqualTo("Tawanda Mpofu");
        assertThat(resp.senderPhone()).isEqualTo(SENDER_PHONE);

        // Both parties are messaged: the recipient's delivery and the
        // sender's own confirmation copy.
        verify(notifications).deliver(v, RECIPIENT_PHONE);
        verify(notifications).deliverSenderCopy(v, SENDER_PHONE);
    }

    @Test
    void senderPhone_defaultsToTheCallersJwtPhone() {
        authenticateWithPhone(SENDER_PHONE);

        service.issue(TENANT, request("Tawanda Mpofu", null, RECIPIENT_PHONE));

        Voucher v = saved();
        assertThat(v.getSenderPhone()).isEqualTo(SENDER_PHONE);
        verify(notifications).deliverSenderCopy(v, SENDER_PHONE);
    }

    @Test
    void selfSend_sameSenderAndRecipientPhone_getsOneMessageNotTwo() {
        service.issue(TENANT, request("Tawanda Mpofu", RECIPIENT_PHONE, RECIPIENT_PHONE));

        verify(notifications).deliver(any(), eq(RECIPIENT_PHONE));
        verify(notifications, never()).deliverSenderCopy(any(), anyString());
    }

    @Test
    void noSenderPhoneAnywhere_noSenderCopy() {
        // No JWT phone (unauthenticated context) and none in the body.
        service.issue(TENANT, request("Tawanda Mpofu", null, RECIPIENT_PHONE));

        Voucher v = saved();
        assertThat(v.getSenderPhone()).isNull();
        verify(notifications, never()).deliverSenderCopy(any(), anyString());
    }

    @Test
    void senderName_isHtmlStripped() {
        service.issue(TENANT,
                request("<script>alert(1)</script>Tawanda", SENDER_PHONE, RECIPIENT_PHONE));

        assertThat(saved().getSenderName()).doesNotContain("<script>").contains("Tawanda");
    }

    @Test
    void bulkStock_carriesNoSender_andMessagesNoOne() {
        // Even with an authenticated caller whose JWT has a phone, bulk must
        // not inherit the sender default — it is unassigned campaign stock,
        // and a per-voucher confirmation would message that phone
        // quantity-times over.
        authenticateWithPhone(SENDER_PHONE);

        service.issueBulk(TENANT, new Dtos.BulkIssueRequest(MERCHANT, null,
                new BigDecimal("5.00"), "USD", null, 3, "CAMPAIGN",
                Voucher.DeliveryChannel.NONE));

        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers, times(3)).save(cap.capture());
        assertThat(cap.getAllValues())
                .allSatisfy(v -> {
                    assertThat(v.getSenderName()).isNull();
                    assertThat(v.getSenderPhone()).isNull();
                });
        verify(notifications, never()).deliverSenderCopy(any(), anyString());
    }
}
