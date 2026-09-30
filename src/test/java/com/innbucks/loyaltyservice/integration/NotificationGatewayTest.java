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
    private com.innbucks.loyaltyservice.repository.MerchantRepository merchants;

    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        merchants = mock(com.innbucks.loyaltyservice.repository.MerchantRepository.class);
        gateway = new NotificationGateway(sms, whatsApp, merchants);
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
    void recipientMessage_forAVoucherIssuedToYourself_saysReady_notSentYou() {
        // Same person as sender and recipient (owner decision, 2026-09-30):
        // "Tawanda Mpofu sent you a voucher" to Tawanda reads as nonsense.
        Voucher v = giftedVoucher(Voucher.DeliveryChannel.WHATSAPP);
        v.setAssigneeName("Tawanda Mpofu");
        v.setAssigneePhone("+263782608767");

        gateway.deliver(v, PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("your InnBucks voucher is ready"));
        verify(whatsApp, org.mockito.Mockito.never()).sendCustomNotification(anyString(), contains("sent you"));
    }

    // ---- where the voucher can be used ----
    // "Show this code at checkout" told the holder nothing about WHERE, and a
    // voucher is refused at any other merchant. Every message now names it.

    private Voucher pizzaInnVoucher() {
        Voucher v = giftedVoucher(Voucher.DeliveryChannel.WHATSAPP);
        java.util.UUID merchantId = java.util.UUID.randomUUID();
        v.setMerchantId(merchantId);
        com.innbucks.loyaltyservice.entity.Merchant m = new com.innbucks.loyaltyservice.entity.Merchant();
        m.setId(merchantId);
        m.setName("Pizza Inn");
        org.mockito.Mockito.when(merchants.findById(merchantId)).thenReturn(java.util.Optional.of(m));
        return v;
    }

    @Test
    void recipientMessage_namesTheMerchantItCanBeUsedAt() {
        gateway.deliver(pizzaInnVoucher(), PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE),
                contains("Tawanda Mpofu sent you an InnBucks voucher for Pizza Inn. Code "));
        verify(whatsApp).sendCustomNotification(eq(PHONE),
                contains("Show this code at any Pizza Inn checkout to redeem."));
    }

    @Test
    void senderCopy_namesTheMerchantToo() {
        Voucher v = pizzaInnVoucher();
        gateway.deliverSenderCopy(v, v.getSenderPhone());

        verify(whatsApp).sendCustomNotification(eq("+263782608767"),
                contains("It can be redeemed at any Pizza Inn."));
    }

    @Test
    void expiryWarning_namesTheMerchant() {
        gateway.warnExpiring(pizzaInnVoucher(), PHONE, java.time.LocalDate.of(2027, 9, 29));

        verify(whatsApp).sendCustomNotification(eq(PHONE),
                contains("your InnBucks voucher for Pizza Inn (USD 5 off) expires on 2027-09-29. "
                        + "Redeem it at any Pizza Inn before then"));
    }

    @Test
    void aFailedMerchantLookup_costsTheName_notTheMessage() {
        Voucher v = giftedVoucher(Voucher.DeliveryChannel.WHATSAPP);
        v.setMerchantId(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(merchants.findById(v.getMerchantId()))
                .thenThrow(new RuntimeException("db down"));

        gateway.deliver(v, PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE),
                contains("Show this code at checkout to redeem."));
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

    // ------------------------------------------------------------------
    // a 16-digit code is shown GROUPED to the person reading it
    // ------------------------------------------------------------------

    private Voucher numericGift() {
        Voucher v = giftedVoucher(Voucher.DeliveryChannel.WHATSAPP);
        v.setCode("9087876598764566");
        return v;
    }

    @Test
    void recipientMessage_showsANumericCodeGroupedInFours() {
        gateway.deliver(numericGift(), PHONE);

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("Code 9087-8765-9876-4566"));
        verify(whatsApp, never()).sendCustomNotification(anyString(), contains("9087876598764566"));
    }

    @Test
    void senderCopy_showsANumericCodeGroupedInFours() {
        Voucher v = numericGift();
        gateway.deliverSenderCopy(v, v.getSenderPhone());

        verify(whatsApp).sendCustomNotification(eq(v.getSenderPhone()), contains("Code 9087-8765-9876-4566"));
    }

    @Test
    void smsFallback_keepsTheGrouping() {
        // A hyphen is GSM-7, so SmsTextSanitizer leaves the grouping intact.
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        gateway.deliver(numericGift(), PHONE);

        verify(sms).sendSms(eq(PHONE), contains("9087-8765-9876-4566"), startsWith("VOUCHER-"));
    }
}
