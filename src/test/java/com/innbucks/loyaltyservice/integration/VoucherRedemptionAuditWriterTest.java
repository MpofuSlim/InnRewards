package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.VoucherRedemption;
import com.innbucks.loyaltyservice.repository.VoucherRedemptionRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The writer that records a REFUSED redemption after its transaction has rolled
 * back — and the class the redemption fixes shipped with no test of any kind.
 *
 * <p>That gap mattered more than an ordinary one, because everything this class
 * promises lives in places a test of its BODY would not reach: the listener
 * phase and the propagation are annotations, and the "nothing escapes" promise
 * turned on when Hibernate flushes rather than on the try/catch that appears to
 * deliver it. Each is asserted here directly — the annotations reflectively, the
 * rest by calling the listener and watching what it does to its collaborators.
 *
 * <p>Pure JUnit + Mockito deliberately: the class has two repository
 * dependencies and no other state, and the sandbox this repo is developed in has
 * no Docker, so a {@code @SpringBootTest} would be an error rather than a test.
 */
class VoucherRedemptionAuditWriterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID VOUCHER = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID CLAIMED_USER = UUID.randomUUID();

    private VoucherRedemptionRepository redemptions;
    private VoucherRepository vouchers;
    private VoucherRedemptionAuditWriter writer;

    @BeforeEach
    void setUp() {
        redemptions = mock(VoucherRedemptionRepository.class);
        vouchers = mock(VoucherRepository.class);
        when(redemptions.saveAndFlush(any(VoucherRedemption.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        writer = new VoucherRedemptionAuditWriter(redemptions, vouchers);
    }

    private static VoucherRedemptionRejectedEvent event(String reason, boolean markExpired) {
        return new VoucherRedemptionRejectedEvent(TENANT, VOUCHER, CLAIMED_USER, MERCHANT,
                "WESTGATE", "192.168.1.100", "abc123def456", reason, markExpired);
    }

    // =====================================================================
    // The row actually gets written — the whole point of the class
    // =====================================================================

    @Test
    void aRefusalIsPersistedAsAREJECTEDRow() {
        writer.onRedemptionRejected(event("not voucher assignee", false));

        ArgumentCaptor<VoucherRedemption> saved = ArgumentCaptor.forClass(VoucherRedemption.class);
        verify(redemptions).saveAndFlush(saved.capture());
        VoucherRedemption r = saved.getValue();

        assertThat(r.getResult()).isEqualTo(VoucherRedemption.Result.REJECTED);
        assertThat(r.getReason()).isEqualTo("not voucher assignee");
        assertThat(r.getTenantId()).isEqualTo(TENANT);
        assertThat(r.getVoucherId()).isEqualTo(VOUCHER);
        assertThat(r.getMerchantId()).isEqualTo(MERCHANT);
        // Recorded verbatim as a CLAIM. The account the gates actually inspect is
        // resolved from the voucher, never from this field.
        assertThat(r.getUserId()).isEqualTo(CLAIMED_USER);
        assertThat(r.getOutletCode()).isEqualTo("WESTGATE");
        assertThat(r.getIpAddress()).isEqualTo("192.168.1.100");
        assertThat(r.getDeviceFingerprint()).isEqualTo("abc123def456");
    }

    // =====================================================================
    // The EXPIRED flip is gated on the flag, not on every refusal
    // =====================================================================

    @Test
    void theExpiryRefusalAlsoFlipsTheVoucher() {
        when(vouchers.markExpiredIfDue(VOUCHER)).thenReturn(1);

        writer.onRedemptionRejected(event("expired", true));

        verify(vouchers).markExpiredIfDue(VOUCHER);
    }

    @Test
    void everyOtherRefusalLeavesTheVoucherStatusAlone() {
        // markExpired is the ONLY thing separating these paths. A refusal for a
        // blocked holder must not age the voucher out as a side effect — the
        // voucher is still perfectly live, it is the person who cannot spend it.
        writer.onRedemptionRejected(event("holder account blocked", false));

        verify(vouchers, never()).markExpiredIfDue(any());
    }

    // =====================================================================
    // Nothing escapes — the promise the class's javadoc makes
    // =====================================================================

    @Test
    void aFailedAuditWriteDoesNotEscapeAndDoesNotTouchTheVoucher() {
        // The customer has already been told EXPIRED by the time this runs. An
        // audit failure must not turn that into something else, and must not
        // half-apply the expiry flip either.
        when(redemptions.saveAndFlush(any(VoucherRedemption.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "value too long for type character varying(80)"));

        assertThatCode(() -> writer.onRedemptionRejected(event("expired", true)))
                .doesNotThrowAnyException();

        verify(vouchers, never()).markExpiredIfDue(any());
    }

    @Test
    void aFailedExpiryFlipDoesNotEscapeEither() {
        when(vouchers.markExpiredIfDue(VOUCHER))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));

        assertThatCode(() -> writer.onRedemptionRejected(event("expired", true)))
                .doesNotThrowAnyException();

        verify(redemptions).saveAndFlush(any(VoucherRedemption.class));
    }

    // =====================================================================
    // The annotations ARE the design — assert them, not a paraphrase of them
    // =====================================================================

    @Test
    void theListenerRunsAfterRollbackInItsOwnTransaction() throws Exception {
        Method m = VoucherRedemptionAuditWriter.class
                .getMethod("onRedemptionRejected", VoucherRedemptionRejectedEvent.class);

        TransactionalEventListener listener = m.getAnnotation(TransactionalEventListener.class);
        assertThat(listener)
                .as("the refusal's own transaction rolls back, so an ordinary listener would "
                        + "have its write discarded exactly like the inline insert this replaced")
                .isNotNull();
        assertThat(listener.phase())
                .as("AFTER_COMMIT never fires here (the transaction rolls back) and BEFORE_COMMIT "
                        + "would still be inside it; AFTER_ROLLBACK is the only phase that both "
                        + "runs and runs after the voucher row's write lock is released")
                .isEqualTo(TransactionPhase.AFTER_ROLLBACK);

        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("AFTER_ROLLBACK runs with no transaction in scope, so without one of its own "
                        + "the insert has nothing to commit in")
                .isNotNull();
        assertThat(tx.propagation())
                .as("REQUIRES_NEW, not REQUIRED: there is no transaction left to join")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void theListenerIsNotAsync() throws Exception {
        // Deliberate, and the Swagger for the EXPIRED refusal depends on it: the
        // flip is documented as landing before the response, which is only true
        // while this runs synchronously inside the redeem call.
        Method m = VoucherRedemptionAuditWriter.class
                .getMethod("onRedemptionRejected", VoucherRedemptionRejectedEvent.class);

        assertThat(m.getAnnotation(org.springframework.scheduling.annotation.Async.class))
                .as("making this @Async would let the refusal's response overtake the EXPIRED "
                        + "flip and make the controller's documented ordering false")
                .isNull();
    }

    // =====================================================================
    // The flush is load-bearing, not a style choice
    // =====================================================================

    @Test
    void theSaveFlushesInsideTheHandlerSoAFailureCanBeCaught() throws Exception {
        // VoucherRedemption.id is @GeneratedValue on a UUID, so a plain save()
        // assigns the id in memory and issues NO SQL — the INSERT would be
        // deferred to the commit, which happens in the transaction interceptor,
        // OUTSIDE the try/catch that claims to contain it. Flushing in-method is
        // what makes "a failed audit write is a WARN, never an escape" true
        // rather than aspirational. Reverting saveAndFlush -> save reddens this.
        writer.onRedemptionRejected(event("revoked", false));

        verify(redemptions).saveAndFlush(any(VoucherRedemption.class));
        verify(redemptions, never()).save(any(VoucherRedemption.class));
    }
}
