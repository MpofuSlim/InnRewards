package com.innbucks.loyaltyservice.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by {@code InvoicingService} once a merchant invoice row is persisted.
 * An {@code @TransactionalEventListener(AFTER_COMMIT)} in
 * {@link InvoiceEmailNotifier} emails the merchant admin only after the invoice
 * actually commits.
 *
 * <p>Carries a value snapshot (not the {@code Invoice}/{@code Merchant}
 * entities) so the post-commit listener composes the email without a DB reload
 * or a detached-entity surprise.
 */
public record InvoiceGeneratedEvent(
        UUID merchantId,
        String merchantName,
        String adminEmail,
        String invoiceNumber,
        LocalDate periodStart,
        LocalDate periodEnd,
        long vouchersIssued,
        long vouchersRedeemed,
        BigDecimal totalAmount,
        String currency) {
}
