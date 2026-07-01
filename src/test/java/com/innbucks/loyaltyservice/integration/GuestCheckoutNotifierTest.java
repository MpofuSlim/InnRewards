package com.innbucks.loyaltyservice.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link GuestCheckoutNotifier}: SMS-primary / WhatsApp-fallback
 * routing and best-effort guarantees. Plain JUnit + Mockito — no Spring, so the
 * {@code @Async} annotation is bypassed and {@code notifyPointsEarned} runs
 * inline on the test thread.
 */
class GuestCheckoutNotifierTest {

    private final SmsNotificationClient sms = mock(SmsNotificationClient.class);
    private final WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
    private final GuestCheckoutNotifier notifier = new GuestCheckoutNotifier(sms, whatsApp);

    @Test
    @DisplayName("SMS succeeds → WhatsApp is never called")
    void smsSucceeds_whatsAppNotCalled() {
        notifier.notifyPointsEarned("Pizza Inn Avondale", "+263771234567",
                new BigDecimal("10.0000"), new BigDecimal("25.0000"));

        verify(sms).sendSms(eq("+263771234567"), anyString(), isNull());
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("SMS fails → WhatsApp is called as the fallback")
    void smsFails_fallsBackToWhatsApp() {
        doThrow(new NotificationDeliveryException("gateway down"))
                .when(sms).sendSms(anyString(), anyString(), any());

        notifier.notifyPointsEarned("Pizza Inn Avondale", "+263771234567",
                new BigDecimal("10"), new BigDecimal("10"));

        verify(sms).sendSms(eq("+263771234567"), anyString(), isNull());
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), anyString());
    }

    @Test
    @DisplayName("both channels fail → method does NOT throw (best-effort)")
    void bothChannelsFail_doesNotThrow() {
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), any());
        doThrow(new NotificationDeliveryException("whatsapp down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThatCode(() -> notifier.notifyPointsEarned(
                "Pizza Inn", "+263771234567", new BigDecimal("5"), new BigDecimal("5")))
                .doesNotThrowAnyException();

        verify(sms).sendSms(anyString(), anyString(), any());
        verify(whatsApp).sendCustomNotification(anyString(), anyString());
    }

    @Test
    @DisplayName("pointsEarned == ZERO → neither channel called (nothing to congratulate)")
    void zeroPoints_noNotification() {
        notifier.notifyPointsEarned("Pizza Inn", "+263771234567",
                BigDecimal.ZERO, new BigDecimal("25"));

        verifyNoInteractions(sms);
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("pointsEarned == null → neither channel called")
    void nullPoints_noNotification() {
        notifier.notifyPointsEarned("Pizza Inn", "+263771234567",
                null, new BigDecimal("25"));

        verifyNoInteractions(sms);
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("blank phone number → neither channel called")
    void blankPhone_noNotification() {
        notifier.notifyPointsEarned("Pizza Inn", "  ",
                new BigDecimal("10"), new BigDecimal("10"));

        verifyNoInteractions(sms);
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("message contains the shop name, points and the InnBucks register CTA")
    void message_containsShopNameAndCta() {
        notifier.notifyPointsEarned("Pizza Inn Avondale", "+263771234567",
                new BigDecimal("10.5000"), new BigDecimal("25.0000"));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq("+263771234567"), messageCaptor.capture(), isNull());
        String message = messageCaptor.getValue();

        org.assertj.core.api.Assertions.assertThat(message)
                .contains("Pizza Inn Avondale")
                .contains("loyalty points")
                .contains("Register/Sign in on the InnBucks App")
                // points formatted with trailing zeros stripped: 10.5000 -> 10.5, 25.0000 -> 25
                .contains("10.5")
                .contains("25");
    }
}
