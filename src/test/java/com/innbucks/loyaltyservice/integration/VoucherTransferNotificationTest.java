package com.innbucks.loyaltyservice.integration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The voucher-transfer messages.
 *
 * <p>The load-bearing assertion here is
 * {@link #theRecipientMessageNeverCarriesTheRedeemableCode()}. The recipient's
 * phone number is typed by the SENDER, so a mistyped digit delivers this to a
 * stranger — and a stranger holding the voucher code could walk into the
 * merchant and redeem it. The voucher is already in the recipient's wallet, so
 * the app is the safe place to reveal the code; the SMS only has to make them
 * open it.
 */
class VoucherTransferNotificationTest {

    private static final String PHONE = "+263771234567";

    private final SmsNotificationClient sms = mock(SmsNotificationClient.class);
    private final WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
    private final MemberActivityNotifier notifier = new MemberActivityNotifier(sms, whatsApp);

    private String sentBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(anyString(), body.capture(), any());
        return body.getValue();
    }

    // ---- the security property ----

    @Test
    void theRecipientMessageNeverCarriesTheRedeemableCode() {
        // The notifier is not even GIVEN the code — the signature has no
        // parameter for it, which is the strongest form this guarantee can
        // take. This test pins the resulting message so a future "make it more
        // helpful" edit can't quietly add one.
        notifier.notifyVoucherReceived(PHONE, "PERCENT", new BigDecimal("10"), "USD",
                LocalDate.of(2027, 8, 20));

        String body = sentBody();
        assertThat(body).doesNotContain("VCH-");
        assertThat(body).contains("Open the app");
    }

    // ---- recipient message content ----

    @Test
    void aPercentVoucherReadsAsPercentOff() {
        notifier.notifyVoucherReceived(PHONE, "PERCENT", new BigDecimal("10"), "USD", null);
        assertThat(sentBody()).contains("10% off");
    }

    @Test
    void anAmountVoucherCarriesItsCurrency() {
        notifier.notifyVoucherReceived(PHONE, "AMOUNT", new BigDecimal("5"), "USD", null);
        assertThat(sentBody()).contains("USD 5 off");
    }

    @Test
    void aFreeItemVoucherDegradesToAGenericNoun_ratherThanPrintingNull() {
        // FREE_ITEM and COMBO carry no numeric value. Formatting them like the
        // numeric types would text a customer "null off" or "0 off".
        notifier.notifyVoucherReceived(PHONE, "FREE_ITEM", null, null, null);

        String body = sentBody();
        assertThat(body).contains("a voucher");
        assertThat(body).doesNotContainIgnoringCase("null");
        assertThat(body).doesNotContain("% off");
    }

    @Test
    void theExpiryIsIncludedWhenTheVoucherHasOne() {
        // Vouchers still expire even though points no longer do, so a silent
        // transfer can simply lapse unused — the date is the part that makes
        // the message actionable.
        notifier.notifyVoucherReceived(PHONE, "PERCENT", new BigDecimal("10"), "USD",
                LocalDate.of(2027, 8, 20));
        assertThat(sentBody()).contains("2027-08-20");
    }

    @Test
    void aNonExpiringVoucherOmitsTheExpirySentenceEntirely() {
        notifier.notifyVoucherReceived(PHONE, "PERCENT", new BigDecimal("10"), "USD", null);
        assertThat(sentBody()).doesNotContain("expires on");
    }

    // ---- sender message ----

    @Test
    void theSenderIsToldItCannotBeSentOnAgain() {
        // Sets the expectation at the moment it matters — the sender has just
        // used the voucher's one and only hop.
        notifier.notifyVoucherSent(PHONE, "PERCENT", new BigDecimal("10"), "USD");

        String body = sentBody();
        assertThat(body).contains("You sent");
        assertThat(body).contains("can't be sent on again");
    }

    // ---- guard rails ----

    @Test
    void aBlankPhoneSendsNothing() {
        notifier.notifyVoucherReceived("  ", "PERCENT", new BigDecimal("10"), "USD", null);
        notifier.notifyVoucherSent(null, "PERCENT", new BigDecimal("10"), "USD");

        verify(sms, never()).sendSms(any(), any(), any());
        verify(whatsApp, never()).sendCustomNotification(any(), any());
    }

    @Test
    void anSmsFailureFallsBackToWhatsApp_andNeverThrows() {
        // Best-effort by contract: the transfer has already committed, so a
        // notification failure must not surface to the caller.
        org.mockito.Mockito.doThrow(new RuntimeException("gateway down"))
                .when(sms).sendSms(anyString(), anyString(), any());

        notifier.notifyVoucherReceived(PHONE, "PERCENT", new BigDecimal("10"), "USD", null);

        verify(whatsApp).sendCustomNotification(anyString(), anyString());
    }

    @Test
    void bothChannelsFailing_isSwallowed() {
        org.mockito.Mockito.doThrow(new RuntimeException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), any());
        org.mockito.Mockito.doThrow(new RuntimeException("whatsapp down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        // No exception escapes — that is the whole assertion.
        notifier.notifyVoucherReceived(PHONE, "PERCENT", new BigDecimal("10"), "USD", null);
        notifier.notifyVoucherSent(PHONE, "PERCENT", new BigDecimal("10"), "USD");
    }
}
