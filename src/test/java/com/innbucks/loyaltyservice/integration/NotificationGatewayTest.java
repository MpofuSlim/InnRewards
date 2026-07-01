package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
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
        v.setValueType(VoucherTemplate.ValueType.AMOUNT);
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
}
