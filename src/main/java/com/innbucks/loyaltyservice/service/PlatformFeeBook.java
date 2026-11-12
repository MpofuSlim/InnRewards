package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.FeeSchedule;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The fee schedules InnBucks has set (V67) for a set of tenants, held in memory
 * so a report or the invoice run can price any number of merchants from ONE
 * query: per tenant its default, per merchant its own.
 *
 * <p>Built from the latest row of each scope
 * ({@code FeeScheduleRepository.findLatestForTenants}). {@link #of} also keeps
 * only the latest row per scope when handed a longer history, so the result is
 * the same whichever list it gets.
 *
 * <p>A withdrawn scope (its latest row is a tombstone) answers as if nothing had
 * ever been set there: the merchant falls through to its tenant's default, and
 * the tenant's default falls through to the legacy fees.
 */
public final class PlatformFeeBook {

    public static final PlatformFeeBook EMPTY = new PlatformFeeBook(Map.of(), Map.of());

    private static final Comparator<FeeSchedule> NEWEST_FIRST = Comparator
            .comparing(FeeSchedule::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(FeeSchedule::getId, Comparator.nullsLast(Comparator.reverseOrder()));

    private final Map<UUID, FeeSchedule> tenantDefaults;
    private final Map<UUID, FeeSchedule> merchantOwn;

    private PlatformFeeBook(Map<UUID, FeeSchedule> tenantDefaults, Map<UUID, FeeSchedule> merchantOwn) {
        this.tenantDefaults = tenantDefaults;
        this.merchantOwn = merchantOwn;
    }

    public static PlatformFeeBook of(Collection<FeeSchedule> rows) {
        if (rows == null || rows.isEmpty()) {
            return EMPTY;
        }
        Map<UUID, FeeSchedule> tenants = new HashMap<>();
        Map<UUID, FeeSchedule> merchants = new HashMap<>();
        for (FeeSchedule row : rows) {
            Map<UUID, FeeSchedule> scope = row.isTenantDefault() ? tenants : merchants;
            UUID key = row.isTenantDefault() ? row.getTenantId() : row.getMerchantId();
            scope.merge(key, row, (a, b) -> NEWEST_FIRST.compare(a, b) <= 0 ? a : b);
        }
        return new PlatformFeeBook(Map.copyOf(tenants), Map.copyOf(merchants));
    }

    /** The merchant's own live schedule, if InnBucks set one and has not withdrawn it. */
    public Optional<FeeSchedule> merchantSchedule(UUID merchantId) {
        return live(merchantOwn.get(merchantId));
    }

    /** The tenant's live default, if InnBucks set one and has not withdrawn it. */
    public Optional<FeeSchedule> tenantSchedule(UUID tenantId) {
        return live(tenantDefaults.get(tenantId));
    }

    private static Optional<FeeSchedule> live(FeeSchedule row) {
        return row == null || row.isCleared() ? Optional.empty() : Optional.of(row);
    }
}
