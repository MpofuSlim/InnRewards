package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.util.SmsTextSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The holder's notice after each voucher use (V61), and the part-used wording
 * of the voucher's own description.
 *
 * <p>Two properties matter most and are asserted on every message: it NEVER
 * carries the voucher code (a bearer credential the holder already has), and it
 * survives {@link SmsTextSanitizer} unchanged — so the SMS fallback says exactly
 * what the WhatsApp said, rather than a version with its punctuation rewritten.
 */
class VoucherUseNoticeTest {

    private static final String PHONE = "+263786546765";
    private static final String CODE = "4829137605128368";
    private static final UUID VOUCHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID MERCHANT = UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final Instant EXPIRES = Instant.parse("2027-10-20T09:00:00Z");

    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private MerchantRepository merchants;
    private NotificationGateway gateway;

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        merchants = mock(MerchantRepository.class);
        Merchant pizzaInn = new Merchant();
        pizzaInn.setName("Pizza Inn");
        when(merchants.findById(MERCHANT)).thenReturn(Optional.of(pizzaInn));
        gateway = new NotificationGateway(sms, whatsApp, merchants);
    }

    private static NotificationGateway.VoucherUse use(String used, String left) {
        return new NotificationGateway.VoucherUse(VOUCHER, MERCHANT, PHONE,
                new BigDecimal(used), new BigDecimal(left), "USD", EXPIRES);
    }

    private String sentOverWhatsApp(NotificationGateway.VoucherUse use) {
        gateway.notifyVoucherUsed(use);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq(PHONE), text.capture());
        return text.getValue();
    }

    private static void assertSafe(String message) {
        assertThat(message).doesNotContain(CODE).doesNotContain("4829");
        assertThat(SmsTextSanitizer.toGsmSafe(message))
                .as("the SMS fallback must carry the same words")
                .isEqualTo(message);
    }

    @Test
    void aPartialUse_saysWhatWasUsed_whatIsLeft_andUntilWhen() {
        String message = sentOverWhatsApp(use("20.0000", "30.0000"));

        assertThat(message).isEqualTo(
                "InnBucks voucher used at Pizza Inn. USD 20.00 used, USD 30.00 left, valid until 20 Oct 2027.");
        assertSafe(message);
    }

    @Test
    void theLastUse_saysTheVoucherIsFullyUsed() {
        String message = sentOverWhatsApp(use("20.0000", "0.0000"));

        assertThat(message).isEqualTo("Your InnBucks voucher for Pizza Inn is now fully used (USD 20.00 this time).");
        assertSafe(message);
    }

    @Test
    void subCentBalances_areNotRoundedAway() {
        String message = NotificationGateway.usedMessage(use("12.5", "0.1250"), "Pizza Inn");

        assertThat(message).contains("USD 12.50 used, USD 0.125 left");
        assertSafe(message);
    }

    @Test
    void aFailedMerchantLookup_costsTheName_neverTheMessage() {
        when(merchants.findById(MERCHANT)).thenThrow(new RuntimeException("db down"));

        String message = sentOverWhatsApp(use("5.0000", "15.0000"));

        assertThat(message).isEqualTo("InnBucks voucher used. USD 5.00 used, USD 15.00 left, valid until 20 Oct 2027.");
        assertSafe(message);
    }

    @Test
    void whatsAppDown_fallsBackToSms_withAUseReference() {
        doThrow(new RuntimeException("wa down")).when(whatsApp).sendCustomNotification(anyString(), anyString());

        gateway.notifyVoucherUsed(use("20.0000", "30.0000"));

        verify(sms).sendSms(eq(PHONE), startsWith("InnBucks voucher used at Pizza Inn."), eq("VOUCHER-USE-" + VOUCHER));
    }

    @Test
    void noHolderPhone_sendsNothing() {
        gateway.notifyVoucherUsed(new NotificationGateway.VoucherUse(VOUCHER, MERCHANT, " ",
                BigDecimal.ONE, BigDecimal.TEN, "USD", EXPIRES));

        verifyNoInteractions(whatsApp, sms);
    }

    // ------------------------------------------------------------------
    // the voucher's own description: what is LEFT once part-used
    // ------------------------------------------------------------------

    private static Voucher voucher(String face, String left) {
        Voucher v = new Voucher();
        v.setId(VOUCHER);
        v.setMerchantId(MERCHANT);
        v.setCode(CODE);
        v.setAssigneeName("Tariro");
        v.setValue(new BigDecimal(face));
        v.setRemainingBalance(left == null ? null : new BigDecimal(left));
        v.setCurrency("USD");
        v.setExpiresAt(EXPIRES);
        return v;
    }

    @Test
    void aResentPartUsedVoucher_quotesWhatIsLeft_neverItsFullValue() {
        String message = gateway.issueMessage(voucher("50.0000", "30.0000"));

        assertThat(message).contains("(USD 30 left of USD 50)").doesNotContain("USD 50 off");
    }

    @Test
    void anUntouchedVoucher_readsExactlyAsBefore() {
        assertThat(gateway.issueMessage(voucher("5.0000", "5.0000"))).contains("(USD 5 off)");
        assertThat(gateway.issueMessage(voucher("5.0000", null))).contains("(USD 5 off)");
    }

    @Test
    void theExpiryWarningOfAPartUsedVoucher_quotesWhatIsLeft() {
        gateway.warnExpiring(voucher("50.0000", "12.5000"), PHONE, java.time.LocalDate.of(2027, 10, 20));

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("(USD 12.5 left of USD 50)"));
        verify(sms, never()).sendSms(any(), any(), any());
    }
}
