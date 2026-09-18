package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.LoyaltyProperties;
import com.innbucks.loyaltyservice.config.SupportedCurrencies;
import com.innbucks.loyaltyservice.dto.Dtos;
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
import com.innbucks.loyaltyservice.security.MerchantAuthz;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the DELIVERED → ISSUED collapse (V48).
 *
 * <p>The retired status was flipped on at save time, before the async send it
 * named had run, and never corrected when that send failed — so it reported an
 * intention as an outcome, and because every voucher issued to a named person
 * carries a delivery channel, no voucher ever stayed ISSUED. These cases exist
 * so nobody reintroduces either half of that: not the status, and not a status
 * flip standing in for a delivery receipt.
 */
class VoucherIssuedStatusTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();

    private static final SupportedCurrencies CURRENCIES = new SupportedCurrencies("USD", "USD");

    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final MerchantAuthz merchantAuthz = mock(MerchantAuthz.class);
    private final LoyaltyRuleRepository rules = mock(LoyaltyRuleRepository.class);
    private final NotificationGateway notifications = mock(NotificationGateway.class);
    private final UserService userService = mock(UserService.class);

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
                        CURRENCIES, new BigDecimal("25")),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        when(vouchers.findByCode(anyString())).thenReturn(Optional.empty());
        when(rules.findApplicable(eq(TENANT), eq(MERCHANT), eq(TransactionType.PURCHASE)))
                .thenReturn(List.of());

        Merchant m = new Merchant();
        m.setId(MERCHANT);
        m.setTenantId(TENANT);
        m.setName("Pizza Inn");
        m.setCurrency("USD");
        when(merchantAuthz.requireCallerAdministersMerchant(TENANT, MERCHANT)).thenReturn(m);

        // The phone-assigned issue path auto-enrols a PENDING LoyaltyUser.
        com.innbucks.loyaltyservice.entity.LoyaltyUser recipient =
                new com.innbucks.loyaltyservice.entity.LoyaltyUser();
        recipient.setId(UUID.randomUUID());
        recipient.setTenantId(TENANT);
        recipient.setPhoneNumber("+263786546765");
        when(userService.findOrCreatePending(eq(TENANT), anyString(), eq(MERCHANT)))
                .thenReturn(recipient);
    }

    private static Dtos.IssueVoucherRequest request(Voucher.DeliveryChannel channel) {
        return new Dtos.IssueVoucherRequest(MERCHANT, null, new BigDecimal("5.00"), "USD", null,
                "+263786546765", "Sedrick Nyanyiwa", null,
                "Tawanda Mpofu", "+263782608767", channel, null);
    }

    private Voucher saved() {
        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).save(cap.capture());
        return cap.getValue();
    }

    // ------------------------------------------------------------------
    // The collapse itself
    // ------------------------------------------------------------------

    @Test
    void issuingWithADeliveryChannel_staysISSUED_butStillStampsTheDispatchAttempt() {
        Dtos.VoucherResponse resp = service.issue(TENANT, request(Voucher.DeliveryChannel.WHATSAPP));

        Voucher v = saved();
        // THE point of V48: a dispatched voucher is ISSUED, not "DELIVERED".
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
        assertThat(resp.status()).isEqualTo("ISSUED");
        // ...and the fact the old status was reaching for survives as a timestamp,
        // which claims only that dispatch was attempted.
        assertThat(v.getDeliveredAt()).isNotNull();
        // The send still happens — collapsing the status changed no behaviour.
        verify(notifications).deliver(v, "+263786546765");
    }

    @Test
    void issuingWithChannelNONE_staysISSUED_andStampsNoDispatch() {
        service.issue(TENANT, request(Voucher.DeliveryChannel.NONE));

        Voucher v = saved();
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
        assertThat(v.getDeliveredAt()).isNull();
    }

    @Test
    void issuingWithNO_CHANNEL_deliversAndStampsTheAttempt() {
        // An absent channel used to SUPPRESS delivery, so a client that
        // stopped sending the (inert) field would have silently stopped
        // sending vouchers. It now behaves like any ordinary issue — the
        // channel never selected a transport, so its absence cannot mean
        // "never contact this customer".
        service.issue(TENANT, request(null));

        Voucher v = saved();
        assertThat(v.getDeliveredAt()).isNotNull();
        verify(notifications).deliver(v, "+263786546765");
    }

    @Test
    void bulkStock_isISSUED_andUndispatched() {
        // Bulk never attempted delivery even before V48, which is why the old
        // ISSUED tab only ever showed campaign stock. Now it is the ONE status
        // both paths share.
        service.issueBulk(TENANT, new Dtos.BulkIssueRequest(MERCHANT, null,
                new BigDecimal("5.00"), "USD", null, 2, "CAMPAIGN",
                Voucher.DeliveryChannel.NONE));

        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers, times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(v -> {
            assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
            assertThat(v.getDeliveredAt()).isNull();
        });
    }

    @Test
    void bulkStock_withNoChannelAtAll_isStillUndispatched() {
        // Bulk has no assignee, so there is nobody to send to — and that, not
        // the channel, is what stops it. Proving it with the channel OMITTED
        // matters now that an absent channel delivers: the guard that keeps
        // bulk quiet must be the missing phone, or dropping the field from a
        // bulk client would start blasting messages at nobody.
        service.issueBulk(TENANT, new Dtos.BulkIssueRequest(MERCHANT, null,
                new BigDecimal("5.00"), "USD", null, 2, "CAMPAIGN", null));

        ArgumentCaptor<Voucher> cap = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers, times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(v -> {
            assertThat(v.getStatus()).isEqualTo(Voucher.Status.ISSUED);
            assertThat(v.getDeliveredAt())
                    .as("no holder phone means no dispatch was attempted, so nothing to stamp")
                    .isNull();
        });
    }

    // ------------------------------------------------------------------
    // The vocabulary, so it cannot creep back
    // ------------------------------------------------------------------

    @Test
    void statusVocabularyHasNoDeliveryState() {
        assertThat(Arrays.stream(Voucher.Status.values()).map(Enum::name))
                .containsExactly("ISSUED", "VIEWED", "REDEEMED", "PARTIALLY_USED", "EXPIRED", "REVOKED")
                .doesNotContain("DELIVERED");
    }

    @Test
    void liveStatusesIsTheOneDefinitionOfAnOutstandingVoucher() {
        // Six sites used to keep their own copy of this list — the reason
        // retiring a single value touched twelve files.
        assertThat(Voucher.LIVE_STATUSES).containsExactly(
                Voucher.Status.ISSUED, Voucher.Status.VIEWED, Voucher.Status.PARTIALLY_USED);
        assertThat(Voucher.LIVE_STATUSES)
                .doesNotContain(Voucher.Status.REDEEMED, Voucher.Status.EXPIRED, Voucher.Status.REVOKED);
    }
}
