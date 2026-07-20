package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Notifies a user that they've just been attached to a tenant (the first-member
 * attach folded into {@code POST /loyalty/tenants}). loyalty holds only the
 * user's UUID, so it resolves the contact (email + phone) via user-service, then
 * pings them.
 *
 * <p><b>Delivery is email-primary, WhatsApp-fallback, SMS-last.</b> A user
 * attached to a tenant is an onboarded business account whose email is on file,
 * so email is the first hop; WhatsApp catches them if email fails or is missing,
 * and SMS is the final safety net. (Previously this was WhatsApp-primary with no
 * email at all.) It runs on the {@code notificationExecutor} pool via
 * {@link Async @Async} so it never delays the tenant-create 201, and it is
 * strictly best-effort: every failure (no contact, no channels, all channels
 * down) is logged and swallowed — this method never throws.
 */
@Slf4j
@Component
public class TenantMemberNotifier {

    private final UserServiceClient userServiceClient;
    private final EmailNotificationClient email;
    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;

    public TenantMemberNotifier(UserServiceClient userServiceClient,
                                EmailNotificationClient email,
                                WhatsAppNotificationClient whatsApp,
                                SmsNotificationClient sms) {
        this.userServiceClient = userServiceClient;
        this.email = email;
        this.whatsApp = whatsApp;
        this.sms = sms;
    }

    @Async("notificationExecutor")
    public void notifyAddedToTenant(UUID userId, String tenantName) {
        if (userId == null) {
            return;
        }

        Optional<UserServiceClient.UserContact> contact = userServiceClient.getUserContact(userId);
        // getUserContact never returns null in prod (it's best-effort and
        // normalises every failure to Optional.empty), but guard anyway so a
        // stubbed/edge collaborator can't NPE this best-effort path.
        if (contact == null || contact.isEmpty()) {
            log.debug("Tenant-attach notification: no contact for userId={}; skipping", userId);
            return;
        }

        String emailAddress = contact.get().email();
        String phone = contact.get().phoneNumber();
        boolean hasEmail = emailAddress != null && !emailAddress.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (!hasEmail && !hasPhone) {
            // No email or phone on file — nothing to notify. Best-effort: skip.
            log.debug("Tenant-attach notification: no email or phone for userId={}; skipping", userId);
            return;
        }

        String message = buildMessage(contact.get().firstName(), tenantName);

        // Primary channel: email (the user is an onboarded business account).
        if (hasEmail) {
            try {
                email.sendEmail(emailAddress, buildSubject(tenantName), message, null);
                return;
            } catch (RuntimeException e) {
                log.warn("Tenant-attach email failed for userId={}, falling back to WhatsApp: {}",
                        userId, e.getMessage());
            }
        }

        if (!hasPhone) {
            // Email was the only channel and it failed — nothing left to try.
            log.warn("Tenant-attach notification: email failed and no phone on file for userId={}", userId);
            return;
        }

        // Fallback channel: WhatsApp.
        try {
            whatsApp.sendCustomNotification(phone, message);
            return;
        } catch (RuntimeException e) {
            log.warn("Tenant-attach WhatsApp failed for {}, falling back to SMS: {}",
                    MsisdnMasking.mask(phone), e.getMessage());
        }

        // Last resort: SMS.
        try {
            sms.sendSms(phone, message, null);
        } catch (RuntimeException e) {
            log.warn("Tenant-attach notification failed on all channels for {}: {}",
                    MsisdnMasking.mask(phone), e.getMessage());
        }
    }

    private String buildSubject(String tenantName) {
        return "You've been added to " + tenantLabel(tenantName) + " on InnBucks";
    }

    private String buildMessage(String firstName, String tenantName) {
        String tenant = tenantLabel(tenantName);
        String greeting = (firstName == null || firstName.isBlank()) ? "" : "Hi " + firstName.trim() + ", ";
        if (greeting.isEmpty()) {
            // No name → capitalised standalone sentence.
            return "You've been added to " + tenant + " on InnBucks.";
        }
        return greeting + "you've been added to " + tenant + " on InnBucks.";
    }

    private String tenantLabel(String tenantName) {
        return (tenantName == null || tenantName.isBlank()) ? "a tenant" : tenantName.trim();
    }
}
