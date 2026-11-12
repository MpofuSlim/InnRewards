package com.innbucks.loyaltyservice.service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Where a pricing caller gets InnBucks' fee schedules ({@link PlatformFeeBook}).
 * {@link PlatformFeeSchedules} is the one implementation; {@link #NONE} is what a
 * service built by hand in a unit test prices with, which is exactly the legacy
 * resolution every pre-V67 test pins.
 */
public interface PlatformFeeBookSource {

    /** Every schedule of these tenants, in one query. An empty collection reads nothing. */
    PlatformFeeBook forTenants(Collection<UUID> tenantIds);

    default PlatformFeeBook forTenant(UUID tenantId) {
        return tenantId == null ? PlatformFeeBook.EMPTY : forTenants(List.of(tenantId));
    }

    PlatformFeeBookSource NONE = tenantIds -> PlatformFeeBook.EMPTY;
}
