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
 * Unit tests for {@link TenantMemberNotifier}: pins the email-primary /
 * WhatsApp-fallback / SMS-last ordering, the best-effort no-throw contract, and
 * the guard rails (no contact / no channels / null userId → nothing touched).
 * Pure JUnit + Mockito, no Spring context — the @Async annotation is a no-op
 * when the bean is called directly.
 */
class TenantMemberNotifierTest {

    private UserServiceClient userServiceClient;
    private EmailNotificationClient email;
    private WhatsAppNotificationClient whatsApp;
    private SmsNotificationClient sms;
    private TenantMemberNotifier notifier;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String PHONE = "+263771234567";
    private static final String EMAIL = "alice@example.com";

    @BeforeEach
    void setUp() {
        userServiceClient = mock(UserServiceClient.class);
        email = mock(EmailNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        notifier = new TenantMemberNotifier(userServiceClient, email, whatsApp, sms);
    }

    private void stubContact(String firstName, String emailAddr, String phone) {
        when(userServiceClient.getUserContact(USER_ID))
                .thenReturn(Optional.of(new UserServiceClient.UserContact(USER_ID, phone, emailAddr, firstName)));
    }

    @Test
    @DisplayName("email succeeds → WhatsApp and SMS never called; email carries subject + tenant + name")
    void emailSuccess_noFallback() {
        stubContact("Alice", EMAIL, PHONE);

        notifier.notifyAddedToTenant(USER_ID, "Innbucks Financial Services");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq(EMAIL), subject.capture(), body.capture(), eq(null));
        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
        assertThat(subject.getValue()).contains("Innbucks Financial Services");
        assertThat(body.getValue()).contains("Innbucks Financial Services");
        assertThat(body.getValue()).contains("Alice");
    }

    @Test
    @DisplayName("email throws → WhatsApp fallback with the same message; SMS not called")
    void emailThrows_whatsAppFallback() {
        stubContact("Alice", EMAIL, PHONE);
        doThrow(new NotificationDeliveryException("email down"))
                .when(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq(PHONE), msg.capture());
        verifyNoInteractions(sms);
        assertThat(msg.getValue()).contains("Acme Coffee");
    }

    @Test
    @DisplayName("email + WhatsApp throw → SMS fallback with the same message")
    void emailAndWhatsAppThrow_smsFallback() {
        stubContact("Alice", EMAIL, PHONE);
        doThrow(new NotificationDeliveryException("email down"))
                .when(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));
        doThrow(new NotificationDeliveryException("wa down"))
                .when(whatsApp).sendCustomNotification(eq(PHONE), anyString());

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq(PHONE), msg.capture(), eq(null));
        assertThat(msg.getValue()).contains("Acme Coffee");
    }

    @Test
    @DisplayName("all three channels throw → no exception escapes (best-effort)")
    void allThrow_noException() {
        stubContact("Alice", EMAIL, PHONE);
        doThrow(new NotificationDeliveryException("email down"))
                .when(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));
        doThrow(new NotificationDeliveryException("wa down"))
                .when(whatsApp).sendCustomNotification(eq(PHONE), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(eq(PHONE), anyString(), eq(null));

        assertThatCode(() -> notifier.notifyAddedToTenant(USER_ID, "Acme Coffee"))
                .doesNotThrowAnyException();

        verify(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));
        verify(whatsApp).sendCustomNotification(eq(PHONE), anyString());
        verify(sms).sendSms(eq(PHONE), anyString(), eq(null));
    }

    @Test
    @DisplayName("no email on file → WhatsApp is used directly (email skipped)")
    void noEmail_whatsAppUsed() {
        stubContact("Alice", "  ", PHONE);

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        verifyNoInteractions(email);
        verify(whatsApp).sendCustomNotification(eq(PHONE), anyString());
    }

    @Test
    @DisplayName("email-only contact, email fails → nothing left; no phone channels touched")
    void emailOnly_emailFails_noPhoneChannels() {
        stubContact("Alice", EMAIL, "  ");   // no phone
        doThrow(new NotificationDeliveryException("email down"))
                .when(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));

        assertThatCode(() -> notifier.notifyAddedToTenant(USER_ID, "Acme Coffee"))
                .doesNotThrowAnyException();

        verify(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));
        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("no contact resolved → no channel is touched")
    void noContact_noChannel() {
        when(userServiceClient.getUserContact(USER_ID)).thenReturn(Optional.empty());

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        verifyNoInteractions(email);
        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("contact present but email AND phone blank → no channel is touched")
    void noEmailNoPhone_noChannel() {
        stubContact("Alice", "  ", "  ");

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        verifyNoInteractions(email);
        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("null userId → no lookup and no channel")
    void nullUserId_noLookup() {
        notifier.notifyAddedToTenant(null, "Acme Coffee");

        verifyNoInteractions(userServiceClient);
        verifyNoInteractions(email);
        verifyNoInteractions(whatsApp);
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("blank first name → message drops the greeting but still names the tenant")
    void blankFirstName_dropsGreeting() {
        stubContact("", EMAIL, PHONE);

        notifier.notifyAddedToTenant(USER_ID, "Acme Coffee");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq(EMAIL), anyString(), body.capture(), eq(null));
        assertThat(body.getValue()).doesNotContain("Hi ,");
        assertThat(body.getValue()).startsWith("You've been added to Acme Coffee");
    }
}
