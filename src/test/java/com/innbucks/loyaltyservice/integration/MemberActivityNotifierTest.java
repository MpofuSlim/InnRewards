package com.innbucks.loyaltyservice.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

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

/**
 * Channel order is SMS-primary, WhatsApp-fallback (flipped from the original
 * WhatsApp-first order as a product decision — SMS reaches every handset).
 */
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
    void earned_usesSmsPrimary_whatsAppNotTouched() {
        notifier.notifyPointsEarned(PHONE, new BigDecimal("50"), new BigDecimal("150"));

        verify(sms).sendSms(eq(PHONE), contains("earned 50"), isNull());
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
    }

    @Test
    void smsFails_fallsBackToWhatsApp_withSameMessage() {
        doThrow(new RuntimeException("sms gw down"))
                .when(sms).sendSms(anyString(), anyString(), isNull());

        notifier.notifyPointsRedeemed(PHONE, new BigDecimal("30"), new BigDecimal("120"));

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("redeemed 30"));
    }

    @Test
    void bothChannelsFail_doesNotThrow() {
        doThrow(new RuntimeException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), isNull());
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThatCode(() -> notifier.notifyPointsEarned(PHONE, new BigDecimal("5"), new BigDecimal("5")))
                .doesNotThrowAnyException();
    }

    @Test
    void transferSent_andReceived_haveDirectionalCopy() {
        notifier.notifyTransferSent(PHONE, new BigDecimal("20"), new BigDecimal("80"));
        verify(sms).sendSms(eq(PHONE), contains("sent 20"), isNull());

        notifier.notifyTransferReceived(PHONE, new BigDecimal("20"), new BigDecimal("40"));
        verify(sms).sendSms(eq(PHONE), contains("received 20"), isNull());
    }

    @Test
    void adjusted_credit_saysCredited() {
        notifier.notifyPointsAdjusted(PHONE, new BigDecimal("15"), new BigDecimal("115"));
        verify(sms).sendSms(eq(PHONE), contains("credited 15"), isNull());
    }

    @Test
    void adjusted_debit_saysReducedByAbsoluteValue() {
        notifier.notifyPointsAdjusted(PHONE, new BigDecimal("-15"), new BigDecimal("85"));
        verify(sms).sendSms(eq(PHONE), contains("reduced by 15"), isNull());
    }

    @Test
    void adjusted_zeroDelta_isNoOp() {
        notifier.notifyPointsAdjusted(PHONE, BigDecimal.ZERO, new BigDecimal("100"));
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void unlocked_sendsActivationCopy() {
        notifier.notifyPointsUnlocked(PHONE);
        verify(sms).sendSms(eq(PHONE), contains("now active"), isNull());
    }

    @Test
    void expiring_sendsRetentionNudge_withAmountAndDate() {
        notifier.notifyPointsExpiring(PHONE, new BigDecimal("40"), LocalDate.of(2026, 7, 28));
        verify(sms).sendSms(eq(PHONE),
                contains("40 of your InnBucks loyalty points expire on 2026-07-28"), isNull());
    }

    @Test
    void expiring_missingDateOrAmount_isNoOp() {
        notifier.notifyPointsExpiring(PHONE, new BigDecimal("40"), null);
        notifier.notifyPointsExpiring(PHONE, BigDecimal.ZERO, LocalDate.now());
        verifyNoInteractions(whatsApp, sms);
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
