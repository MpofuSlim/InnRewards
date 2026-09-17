package com.innbucks.loyaltyservice.config;

import com.innbucks.loyaltyservice.entity.Voucher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code status} query parameter on the voucher report endpoints to
 * {@link Voucher.Status}, and accepts the retired {@code DELIVERED} as an
 * alias for {@code ISSUED}.
 *
 * <p><b>Why this exists.</b> V48 merged DELIVERED into ISSUED. Seven endpoints
 * take a {@code Voucher.Status} as a request parameter (six report endpoints
 * plus {@code GET /loyalty/vouchers}), and Spring's default enum binding
 * rejects an unknown name with a 400. The operator console ships a DELIVERED
 * filter tab TODAY, so without this converter the backend deploy would break
 * that tab until a separate frontend release removed it — a self-inflicted
 * outage in the rolling window, for a value whose correct answer we know.
 *
 * <p>The alias is not a fudge: the vouchers that tab used to show are exactly
 * the vouchers ISSUED now returns (V48 rewrote the rows), so the old request
 * gets the right answer rather than an error. The service NEVER emits
 * DELIVERED — this is input tolerance only, in the same spirit as the fleet's
 * permissive inbound timestamp parsing, which exists so in-flight clients
 * survive a rolling deploy.
 *
 * <p><b>Removal condition:</b> delete this class (and its test) once the
 * console no longer sends {@code status=DELIVERED}. Watch
 * {@code loyalty.voucher.status.legacy_alias} — when it stops incrementing in
 * production, no client is relying on it any more. Nothing else needs the
 * converter: without it, Spring's built-in enum binding handles the six live
 * values identically.
 */
@Component
@Slf4j
public class VoucherStatusConverter implements Converter<String, Voucher.Status> {

    /** The status name retired by V48, still accepted on input. */
    static final String RETIRED_DELIVERED = "DELIVERED";

    private final LoyaltyMetrics metrics;

    public VoucherStatusConverter(LoyaltyMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Registering a {@code Converter<String, Voucher.Status>} REPLACES Spring's
     * default binding for this enum, so this method must handle every live
     * value too — not just the alias. An unknown name throws
     * {@link IllegalArgumentException}, which Spring surfaces as the same 400
     * the default binder produced, so nothing about the error contract moves.
     */
    @Override
    public Voucher.Status convert(String source) {
        String name = source == null ? "" : source.trim().toUpperCase(java.util.Locale.ROOT);
        if (RETIRED_DELIVERED.equals(name)) {
            // Count it rather than log per call: a console still on the old tab
            // would otherwise fill the log with a line per page view.
            metrics.incLegacyVoucherStatusAlias();
            return Voucher.Status.ISSUED;
        }
        return Voucher.Status.valueOf(name);
    }
}
