package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One effective-dated entry in the FX table (multi-currency design: USD base +
 * ZAR + ZWG): how many units of {@link #currency} one USD buys.
 *
 * <p><b>Two scopes, bank-rate default + tenant override.</b> A PLATFORM row
 * ({@code tenantId} null) is the default every tenant inherits — the "bank
 * rate", entered by SUPER_ADMIN today and by the scheduled feed job in a later
 * phase. A TENANT row ({@code tenantId} set) is that tenant's own override and
 * beats every platform row for that tenant. Precedence: tenant override →
 * platform ADMIN → platform FEED (see
 * {@link com.innbucks.loyaltyservice.repository.ExchangeRateRepository#currentRate}).
 * The BASE currency (USD) is never stored in either scope: USD/USD is identity,
 * and the service refuses to write it.
 *
 * <p><b>Append-only + effective-dated.</b> A rate is never mutated or deleted; a
 * new row supersedes it. The rate in force at instant {@code T} is the row with
 * the greatest {@code effectiveFrom <= T} for the currency (ties broken by
 * {@code createdAt}). That history is the audit trail of every rate any money
 * row was ever valued at — essential for ZWG, which moves fast. See
 * {@link com.innbucks.loyaltyservice.service.ExchangeRateService}.
 */
@Entity
@Table(name = "exchange_rates", indexes = {
        @Index(name = "idx_exchange_rate_lookup", columnList = "currency,tenant_id,effective_from,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ExchangeRate {

    /** Who wrote the row — a human decision vs the (later-phase) feed job. */
    public enum Source { ADMIN, FEED }

    @Id
    private UUID id;

    /**
     * Null = a PLATFORM row (the inherited "bank rate" default); set = that
     * tenant's own override, which beats every platform row for that tenant.
     */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** The QUOTE currency (ISO 4217) — units of this per 1 USD. Never the base. */
    @Column(nullable = false, length = 8)
    private String currency;

    /**
     * Units of {@link #currency} per 1 USD. {@code NUMERIC(19,6)} — a rate is a
     * multiplier, not an amount, so it gets two more decimals than the (19,4)
     * money columns. Strictly positive when set.
     *
     * <p>NULL only on a {@link #cleared} tombstone, which has no rate by
     * definition. The V39 CHECK enforces the pairing: a row either sets a
     * positive rate or clears the override, never neither and never both.
     */
    @Column(name = "rate_per_usd", precision = 19, scale = 6)
    private BigDecimal ratePerUsd;

    /**
     * TRUE = this row is a TOMBSTONE revoking its scope's override rather than
     * setting a rate (V39). Resolution finds it like any other row — latest
     * {@code effectiveFrom <= T} — and falls through to the next scope down.
     *
     * <p>That is how a tenant goes BACK to the platform ("bank") rate without
     * mutating history: append-only is preserved, the decision is attributable
     * and effective-dated, and the trail reads as what actually happened rather
     * than a row quietly vanishing. Only meaningful at tenant scope — a platform
     * tombstone would just mean {@code NO_FX_RATE}, so the service refuses one.
     */
    @Column(nullable = false)
    private boolean cleared = false;

    /**
     * When this rate starts being in force — distinct from {@link #createdAt} so
     * an operator can schedule a change ahead of time; the resolver ignores a
     * future-dated row until its instant arrives.
     */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.ADMIN;

    /** JWT userId of the operator (ADMIN rows); null for FEED rows. */
    @Column(name = "created_by")
    private UUID createdBy;

    /** The WHY ("RBZ interbank 2026-09-02"); mandatory when forcing past the
     *  sanity band. Shown in the history endpoint. */
    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
