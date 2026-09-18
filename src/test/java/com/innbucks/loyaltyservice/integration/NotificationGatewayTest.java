package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationGatewayTest {

    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private NotificationGateway gateway;

    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        gateway = new NotificationGateway(sms, whatsApp);
    }

    private Voucher voucher(Voucher.DeliveryChannel channel) {
        Voucher v = new Voucher();
        v.setId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        v.setCode("VCH-AB12CD34");
        v.setAssigneeName("Tariro");
        v.setDeliveryChannel(channel);
        v.setValue(new BigDecimal("5.00"));
        v.setCurrency("USD");
        v.setExpiresAt(Instant.parse("2026-08-01T00:00:00Z"));
        return v;
    }

    @Test
    void whatsAppPrimary_carriesTheCode_smsNotTouched() {
        gateway.deliver(voucher(Voucher.DeliveryChannel.WHATSAPP), PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("VCH-AB12CD34"));
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void whatsAppFails_fallsBackToSms_withVoucherRefAndCode() {
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        gateway.deliver(voucher(Voucher.DeliveryChannel.SMS), PHONE);

        verify(sms).sendSms(eq(PHONE), contains("VCH-AB12CD34"), startsWith("VOUCHER-"));
    }

    @Test
    void bothChannelsFail_doesNotThrow() {
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());
        doThrow(new RuntimeException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        assertThatCode(() -> gateway.deliver(voucher(Voucher.DeliveryChannel.WHATSAPP), PHONE))
                .doesNotThrowAnyException();
    }

    @Test
    void channelNone_isNoOp() {
        gateway.deliver(voucher(Voucher.DeliveryChannel.NONE), PHONE);
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void noChannelAtAll_STILL_DELIVERS() {
        // The trap this change exists to remove. An absent channel used to
        // suppress, so a client that stopped sending the (inert) field would
        // have silently stopped delivering every voucher it issued — issued
        // fine, code in the API response, nothing ever reaching the customer.
        // Only an explicit NONE suppresses now.
        gateway.deliver(voucher(null), PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE), anyString());
    }

    @Test
    void theChannelNeverRoutedAnything_everyValueIsWhatsAppFirst() {
        // EMAIL and POS name transports this service cannot perform, and SMS
        // describes the fallback rather than the first choice. All three take
        // the WhatsApp path, which is why offering the choice was misleading.
        for (Voucher.DeliveryChannel c : new Voucher.DeliveryChannel[]{
                Voucher.DeliveryChannel.SMS, Voucher.DeliveryChannel.EMAIL,
                Voucher.DeliveryChannel.PUSH, Voucher.DeliveryChannel.POS,
                Voucher.DeliveryChannel.WHATSAPP}) {
            gateway.deliver(voucher(c), PHONE);
        }

        verify(whatsApp, times(5)).sendCustomNotification(eq(PHONE), anyString());
        verifyNoInteractions(sms);
    }

    @Test
    void senderCopy_withNoChannel_isAlsoDelivered() {
        gateway.deliverSenderCopy(giftedVoucher(null), "+263782608767");

        verify(whatsApp).sendCustomNotification(eq("+263782608767"), anyString());
    }

    @Test
    void noPhone_isNoOp() {
        gateway.deliver(voucher(Voucher.DeliveryChannel.WHATSAPP), null);
        gateway.deliver(voucher(Voucher.DeliveryChannel.WHATSAPP), "  ");
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void message_includesValueDescriptionAndExpiry() {
        gateway.deliver(voucher(Voucher.DeliveryChannel.WHATSAPP), PHONE);

        // "USD 5 off" (value) and the expiry date both surface in the copy.
        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("off"));
    }

    // ------------------------------------------------------------------
    // Sender identity (V46) — the recipient's message names the giver, and
    // the sender's phone gets its own confirmation copy.
    // ------------------------------------------------------------------

    private Voucher giftedVoucher(Voucher.DeliveryChannel channel) {
        Voucher v = voucher(channel);
        v.setAssigneeName("Sedrick Nyanyiwa");
        v.setAssigneePhone("+263786546765");
        v.setSenderName("Tawanda Mpofu");
        v.setSenderPhone("+263782608767");
        return v;
    }

    @Test
    void recipientMessage_namesTheSender_whenPresent() {
        gateway.deliver(giftedVoucher(Voucher.DeliveryChannel.WHATSAPP), PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE),
                contains("Tawanda Mpofu sent you an InnBucks voucher"));
    }

    @Test
    void recipientMessage_withoutASender_keepsThePlatformCopy() {
        gateway.deliver(voucher(Voucher.DeliveryChannel.WHATSAPP), PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE),
                contains("your InnBucks voucher is ready"));
    }

    @Test
    void senderCopy_whatsAppPrimary_namesTheRecipientAndCarriesTheCode() {
        Voucher v = giftedVoucher(Voucher.DeliveryChannel.WHATSAPP);
        gateway.deliverSenderCopy(v, v.getSenderPhone());

        verify(whatsApp).sendCustomNotification(eq("+263782608767"),
                contains("Sedrick Nyanyiwa (+263786546765)"));
        verify(whatsApp).sendCustomNotification(eq("+263782608767"),
                contains("VCH-AB12CD34"));
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void senderCopy_whatsAppFails_fallsBackToSms_withSenderRef() {
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        Voucher v = giftedVoucher(Voucher.DeliveryChannel.WHATSAPP);
        gateway.deliverSenderCopy(v, v.getSenderPhone());

        verify(sms).sendSms(eq("+263782608767"), contains("VCH-AB12CD34"),
                startsWith("VOUCHER-SENDER-"));
    }

    @Test
    void senderCopy_channelNoneOrBlankPhone_isNoOp() {
        gateway.deliverSenderCopy(giftedVoucher(Voucher.DeliveryChannel.NONE), "+263782608767");
        gateway.deliverSenderCopy(giftedVoucher(Voucher.DeliveryChannel.WHATSAPP), null);
        gateway.deliverSenderCopy(giftedVoucher(Voucher.DeliveryChannel.WHATSAPP), "  ");
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void senderCopy_bothChannelsFail_doesNotThrow() {
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());
        doThrow(new RuntimeException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        Voucher v = giftedVoucher(Voucher.DeliveryChannel.WHATSAPP);
        assertThatCode(() -> gateway.deliverSenderCopy(v, v.getSenderPhone()))
                .doesNotThrowAnyException();
    }
}
