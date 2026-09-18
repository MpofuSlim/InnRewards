package com.innbucks.loyaltyservice.integration;

import java.util.UUID;

/**
 * Published by {@code VoucherService.doRedeem} on every REFUSED redemption
 * attempt, and written to {@code voucher_redemptions} by
 * {@link VoucherRedemptionAuditWriter} once the refusal's transaction has rolled
 * back.
 *
 * <p><b>Why an event and not just an insert.</b> Every refusal branch follows the
 * pattern "record the attempt, then THROW", from inside a class-level
 * {@code @Transactional} service, and {@code LoyaltyException} is a
 * {@code RuntimeException} — so the insert rolled back with the refusal it was
 * recording. `VoucherRedemption.Result.REJECTED` existed, six branches wrote it,
 * and the table could only ever hold SUCCESS rows.
 *
 * <p><b>Why it cannot simply copy {@code FraudService.record}'s
 * {@code REQUIRES_NEW}</b>, which solves the identical problem one line away:
 * {@code fraud_attempts} has no foreign key, while
 * {@code voucher_redemptions.voucher_id REFERENCES vouchers(id)} does. The
 * refusing transaction is holding a {@code PESSIMISTIC_WRITE} lock on that very
 * voucher row ({@code lockByCode}), and Postgres takes a {@code FOR KEY SHARE}
 * lock on the parent row to validate the foreign key — which conflicts. A second
 * transaction would block on a lock only the first can release, while the first
 * waits for the second to return: not a deadlock Postgres can detect and break,
 * because from its side the outer session is merely idle in transaction. It
 * would hang the redeem. Waiting for AFTER_ROLLBACK is what makes the write
 * safe: by then the lock is gone and the parent row is still there.
 *
 * <p>Carries a value snapshot rather than the {@code Voucher} entity, like
 * {@link InvoiceGeneratedEvent}, so the listener writes its row without
 * reloading or touching a detached instance.
 *
 * @param userId the {@code userId} the REQUEST claimed, recorded verbatim. It is
 *               not the holder and is not validated — see
 *               {@code FraudService.record}'s note on the same field. The
 *               account actually gated on is resolved from the voucher.
 * @param markExpired true only for the expiry refusal, where the listener also
 *                    flips the voucher to {@code EXPIRED}. That flip used to be
 *                    set on the managed entity inside the refusing transaction,
 *                    and was discarded with it — the controller's Swagger has
 *                    always promised it, so it is applied here instead, for the
 *                    same lock reason as the row above.
 */
public record VoucherRedemptionRejectedEvent(
        UUID tenantId,
        UUID voucherId,
        UUID userId,
        UUID merchantId,
        String outletCode,
        String ipAddress,
        String deviceFingerprint,
        String reason,
        boolean markExpired) {
}
