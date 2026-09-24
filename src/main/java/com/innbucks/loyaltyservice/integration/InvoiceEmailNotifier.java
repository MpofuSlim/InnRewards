package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Emails a merchant's invoice to the people who run the business that owns it,
 * best-effort, once the invoice row has COMMITTED. Listening on
 * {@link TransactionPhase#AFTER_COMMIT} (rather than sending inline in
 * {@code InvoicingService}) guarantees we never email an invoice that then
 * rolls back, and {@code @Async} keeps the send off the nightly scheduler's
 * thread.
 *
 * <p>The recipients are the OWNER and ADMIN members of the merchant's
 * organization, resolved from user-service at send time. It used to be the
 * merchant's single {@code admin_email}; resolving at send time means a
 * colleague added to the business receives the next invoice, and one who has
 * left does not.
 *
 * <p>A delivery failure is swallowed, per recipient — a missed email must never
 * wedge the invoicing job, and one bad address must not cost the others theirs.
 * The invoice is already in the DB and on the billing page, so operators can
 * resend if needed.
 */
@Slf4j
@Component
public class InvoiceEmailNotifier {

    private final EmailNotificationClient email;
    private final UserServiceClient userService;

    public InvoiceEmailNotifier(EmailNotificationClient email, UserServiceClient userService) {
        this.email = email;
        this.userService = userService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceGenerated(InvoiceGeneratedEvent e) {
        if (e.organizationId() == null) {
            log.info("Invoice {} generated for merchant {}, which no organization owns — skipping email",
                    e.invoiceNumber(), e.merchantId());
            return;
        }
        List<String> recipients = userService.organizationAdminEmails(e.organizationId());
        if (recipients.isEmpty()) {
            log.info("Invoice {} generated for merchant {} but its organization has nobody to email",
                    e.invoiceNumber(), e.merchantId());
            return;
        }
        int sent = 0;
        for (String to : recipients) {
            try {
                email.sendEmail(to, subject(e), body(e), e.invoiceNumber());
                sent++;
            } catch (RuntimeException ex) {
                // Best-effort, per recipient: the invoice exists regardless.
                log.warn("Failed to email invoice {} for merchant {} to one of its admins: {}",
                        e.invoiceNumber(), e.merchantId(), ex.getMessage());
            }
        }
        log.info("Invoice {} for merchant {} emailed to {} of {} organization admin(s)",
                e.invoiceNumber(), e.merchantId(), sent, recipients.size());
    }

    private static String subject(InvoiceGeneratedEvent e) {
        return "InnBucks loyalty invoice " + e.invoiceNumber();
    }

    private static String body(InvoiceGeneratedEvent e) {
        String cur = e.currency() == null ? "" : e.currency() + " ";
        String who = (e.merchantName() == null || e.merchantName().isBlank()) ? "Merchant" : e.merchantName();
        return "Hello " + who + ",\n\n"
                + "Your InnBucks loyalty invoice for the period "
                + e.periodStart() + " to " + e.periodEnd() + " is ready.\n\n"
                + "Invoice number:    " + e.invoiceNumber() + "\n"
                + "Vouchers issued:   " + e.vouchersIssued() + "\n"
                + "Vouchers redeemed: " + e.vouchersRedeemed() + "\n"
                + "Amount due:        " + cur + e.totalAmount() + "\n\n"
                + "You can view and settle this invoice from your merchant billing dashboard.";
    }
}
