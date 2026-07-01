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
 * user's UUID, so it resolves the phone via user-service, then pings them.
 *
 * <p>Delivery is WhatsApp-primary, SMS-fallback (try WhatsApp; only if WhatsApp
 * fails, try SMS) — the REVERSE of {@link GuestCheckoutNotifier}, whose walk-in
 * guest may have no WhatsApp yet. A user attached to a tenant is an onboarded
 * account, so WhatsApp is the better first hop. It runs on the
 * {@code notificationExecutor} pool via {@link Async @Async} so it never delays
 * the tenant-create 201, and it is strictly best-effort: every failure (no
 * contact, blank phone, both channels down) is logged and swallowed — this
 * method never throws.
 */
@Slf4j
@Component
public class TenantMemberNotifier {

    private final UserServiceClient userServiceClient;
    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;

    public TenantMemberNotifier(UserServiceClient userServiceClient,
                                WhatsAppNotificationClient whatsApp,
                                SmsNotificationClient sms) {
        this.userServiceClient = userServiceClient;
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
        String phone = contact.get().phoneNumber();
        if (phone == null || phone.isBlank()) {
            // No contact resolved (unknown user / user-service down) or no phone
            // on file — nothing to notify. Best-effort: skip quietly.
            log.debug("Tenant-attach notification: no phone for userId={}; skipping", userId);
            return;
        }

        String message = buildMessage(contact.get().firstName(), tenantName);

        // Primary channel: WhatsApp (the user is an onboarded account).
        try {
            whatsApp.sendCustomNotification(phone, message);
            return;
        } catch (RuntimeException e) {
            log.warn("Tenant-attach WhatsApp failed for {}, falling back to SMS: {}",
                    MsisdnMasking.mask(phone), e.getMessage());
        }

        // Fallback channel: SMS.
        try {
            sms.sendSms(phone, message, null);
        } catch (RuntimeException e) {
            log.warn("Tenant-attach notification failed on both channels for {}: {}",
                    MsisdnMasking.mask(phone), e.getMessage());
        }
    }

    private String buildMessage(String firstName, String tenantName) {
        String tenant = (tenantName == null || tenantName.isBlank()) ? "a tenant" : tenantName.trim();
        String greeting = (firstName == null || firstName.isBlank()) ? "" : "Hi " + firstName.trim() + ", ";
        if (greeting.isEmpty()) {
            // No name → capitalised standalone sentence.
            return "You've been added to " + tenant + " on InnBucks.";
        }
        return greeting + "you've been added to " + tenant + " on InnBucks.";
    }
}
