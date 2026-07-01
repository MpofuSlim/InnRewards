package com.innbucks.loyaltyservice.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MemberActivityNotifierTest {

    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private MemberActivityNotifier notifier;

    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        notifier = new MemberActivityNotifier(sms, whatsApp);
    }

    @Test
    void earned_usesWhatsAppPrimary_smsNotTouched() {
        notifier.notifyPointsEarned(PHONE, new BigDecimal("50"), new BigDecimal("150"));

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("earned 50"));
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void whatsAppFails_fallsBackToSms_withSameMessage() {
        doThrow(new RuntimeException("gw down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        notifier.notifyPointsRedeemed(PHONE, new BigDecimal("30"), new BigDecimal("120"));

        verify(sms).sendSms(eq(PHONE), contains("redeemed 30"), isNull());
    }

    @Test
    void bothChannelsFail_doesNotThrow() {
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());
        doThrow(new RuntimeException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        assertThatCode(() -> notifier.notifyPointsEarned(PHONE, new BigDecimal("5"), new BigDecimal("5")))
                .doesNotThrowAnyException();
    }

    @Test
    void transferSent_andReceived_haveDirectionalCopy() {
        notifier.notifyTransferSent(PHONE, new BigDecimal("20"), new BigDecimal("80"));
        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("sent 20"));

        notifier.notifyTransferReceived(PHONE, new BigDecimal("20"), new BigDecimal("40"));
        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("received 20"));
    }

    @Test
    void adjusted_credit_saysCredited() {
        notifier.notifyPointsAdjusted(PHONE, new BigDecimal("15"), new BigDecimal("115"));
        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("credited 15"));
    }

    @Test
    void adjusted_debit_saysReducedByAbsoluteValue() {
        notifier.notifyPointsAdjusted(PHONE, new BigDecimal("-15"), new BigDecimal("85"));
        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("reduced by 15"));
    }

    @Test
    void adjusted_zeroDelta_isNoOp() {
        notifier.notifyPointsAdjusted(PHONE, BigDecimal.ZERO, new BigDecimal("100"));
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void unlocked_sendsActivationCopy() {
        notifier.notifyPointsUnlocked(PHONE);
        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("now active"));
    }

    @Test
    void blankPhone_isNoOp() {
        notifier.notifyPointsEarned("  ", new BigDecimal("10"), new BigDecimal("10"));
        notifier.notifyPointsUnlocked(null);
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void nonPositiveAmount_isNoOp() {
        notifier.notifyPointsEarned(PHONE, BigDecimal.ZERO, new BigDecimal("10"));
        notifier.notifyPointsRedeemed(PHONE, new BigDecimal("-5"), new BigDecimal("10"));
        verifyNoInteractions(whatsApp, sms);
    }
}
