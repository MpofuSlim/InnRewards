package com.innbucks.loyaltyservice.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails a merchant its invoice, best-effort, once the invoice row has
 * COMMITTED. Listening on {@link TransactionPhase#AFTER_COMMIT} (rather than
 * sending inline in {@code InvoicingService}) guarantees we never email an
 * invoice that then rolls back, and {@code @Async} keeps the send off the
 * nightly scheduler's thread.
 *
 * <p>A delivery failure is swallowed — a missed email must never wedge the
 * invoicing job. The invoice is already in the DB and on the merchant's billing
 * page, so operators can resend if needed.
 */
@Slf4j
@Component
public class InvoiceEmailNotifier {

    private final EmailNotificationClient email;

    public InvoiceEmailNotifier(EmailNotificationClient email) {
        this.email = email;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceGenerated(InvoiceGeneratedEvent e) {
        if (e.adminEmail() == null || e.adminEmail().isBlank()) {
            log.info("Invoice {} generated for merchant {} but no admin email on file — skipping email",
                    e.invoiceNumber(), e.merchantId());
            return;
        }
        try {
            email.sendEmail(e.adminEmail(), subject(e), body(e), e.invoiceNumber());
            log.info("Invoice {} emailed to merchant {}", e.invoiceNumber(), e.merchantId());
        } catch (RuntimeException ex) {
            // Best-effort: the invoice exists regardless; a failed send must not
            // break the job. Operators can resend from the billing dashboard.
            log.warn("Failed to email invoice {} to merchant {}: {}",
                    e.invoiceNumber(), e.merchantId(), ex.getMessage());
        }
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
                + "You can view and settle this invoice from your merchant billing dashboard.\n\n"
                + "The InnBucks Team";
    }
}
