package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TenantMemberNotifier}: pins the WhatsApp-primary /
 * SMS-fallback ordering, the best-effort no-throw contract, and the guard rails
 * (no contact / blank phone / null userId → no channel touched). Pure JUnit +
 * Mockito, no Spring context — the @Async annotation is a no-op when the bean is
 * called directly.
 */
class TenantMemberNotifierTest {

    private UserServiceClient userServiceClient;
    private WhatsAppNotificationClient whatsApp;
    private SmsNotificationClient sms;
    private TenantMemberNotifier notifier;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String PHONE = "+263771234567";

    @BeforeEach
    void setUp() {
        userServiceClient = mock(UserServiceClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        notifier = new TenantMemberNotifier(userServiceClient, whatsApp, sms);
    }

    private void stubContact(String firstName, String phone) {
        when(userServiceClient.getUserContact(USER_ID))
                .thenReturn(Optional.of(new UserServiceClient.UserContact(USER_ID, phone, "a@b.com", firstName)));
    }

    @Test
    @DisplayName("WhatsApp succeeds → SMS is never called; message carries the tenant name")
    void whatsAppSuccess_smsNotCalled() {
        stubContact("Alice", PHONE);

        notifier.notifyAddedToTenant(USER_ID, "Innbucks Financial Services");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq(PHONE), msg.capture());
        verifyNoInteractions(sms);
        assertThat(msg.getValue()).contains("Innbucks Financial Services");
        assertThat(msg.getValue()).contains("Alice");
    }

    @Test
    @DisplayName("WhatsApp throws → SMS is called as fallback with the same message")
    void whatsAppThrows_smsFallback() {
        stubContact("Alice", PHONE);
        doThrow(new NotificationDeliveryException("wa down"))
                .when(whatsApp).sendCustomNotification(eq(PHONE), anyString());

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq(PHONE), msg.capture(), eq(null));
        assertThat(msg.getValue()).contains("Acme Coffee");
    }

    @Test
    @DisplayName("both channels throw → no exception escapes (best-effort)")
    void bothThrow_noException() {
        stubContact("Alice", PHONE);
        doThrow(new NotificationDeliveryException("wa down"))
                .when(whatsApp).sendCustomNotification(eq(PHONE), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(eq(PHONE), anyString(), eq(null));

        assertThatCode(() -> notifier.notifyAddedToTenant(USER_ID, "Acme Coffee"))
                .doesNotThrowAnyException();

        verify(whatsApp).sendCustomNotification(eq(PHONE), anyString());
        verify(sms).sendSms(eq(PHONE), anyString(), eq(null));
    }

    @Test
    @DisplayName("no contact resolved → neither channel is touched")
    void noContact_noChannel() {
        when(userServiceClient.getUserContact(USER_ID)).thenReturn(Optional.empty());

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("contact present but phone blank → neither channel is touched")
    void blankPhone_noChannel() {
        stubContact("Alice", "   ");

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("null userId → no lookup and no channel")
    void nullUserId_noLookup() {
        notifier.notifyAddedToTenant(null, "Acme Coffee");

        verifyNoInteractions(userServiceClient);
        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("blank first name → message drops the greeting but still names the tenant")
    void blankFirstName_dropsGreeting() {
        stubContact("", PHONE);

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq(PHONE), msg.capture());
        assertThat(msg.getValue()).doesNotContain("Hi ,");
        assertThat(msg.getValue()).startsWith("You've been added to Acme Coffee");
    }
}
