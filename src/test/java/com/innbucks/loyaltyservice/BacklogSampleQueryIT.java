package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres coverage for {@code LoyaltyUserRepository.sampleUnregisteredBacklogPhones}
 * — the native query behind {@code InnbucksValidateBacklogSweeper}. It uses
 * {@code ORDER BY random()}, a DISTINCT-in-subselect, a native {@code LIMIT
 * :batch} bind and a status/status_reason predicate, none of which a Mockito
 * stub exercises; H2 also does not faithfully mimic Postgres here. This is the
 * test the sweeper unit test's javadoc points at.
 *
 * <p>The load-bearing case is the REVOCATION exclusion (the reason this query
 * exists as its own method rather than reusing {@code findStaleUnregistered}'s
 * live-only filter): a phone with a revoked registration must NOT be sampled, or
 * the sweep would re-validate and quietly reinstate what an operator revoked.
 */
class BacklogSampleQueryIT extends PostgresIntegrationTestBase {

    @Autowired LoyaltyUserRepository users;
    @Autowired PhoneRegistrationRepository registrations;
    @Autowired TenantRepository tenants;

    /** loyalty_users.tenant_id has a FK to tenants, so a projection needs a real one. */
    private UUID newTenant() {
        Tenant t = new Tenant();
        t.setCode("backlog-it-" + System.nanoTime());
        t.setName("Backlog Sample IT");
        return tenants.save(t).getId();
    }

    private void projection(UUID tenantId, String phone,
                            LoyaltyUser.Status status, LoyaltyUser.StatusReason reason) {
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(tenantId);
        u.setPhoneNumber(phone);
        u.setStatus(status);
        u.setStatusReason(reason);
        u.setCreatedAt(Instant.now());
        users.save(u);
    }

    private void registration(String phone, boolean revoked) {
        PhoneRegistration r = new PhoneRegistration();
        r.setPhoneNumber(phone);
        r.setRegisteredAt(Instant.now());
        r.setSource(PhoneRegistration.Source.INNBUCKS_VALIDATE);
        r.setCreatedAt(Instant.now());
        if (revoked) {
            r.setRevokedAt(Instant.now());
            r.setRevokedReason("eligibility decision reversed");
        }
        registrations.save(r);
    }

    // A per-test unique phone prefix. The Postgres container is shared across IT
    // classes and NOT truncated between them, so fixtures must not collide with
    // other suites' rows (phone_registrations.phone_number is a PK), and
    // assertions must be containment-based, not whole-table equality. A batch
    // far larger than the whole table is used for presence checks so ORDER BY
    // random() + LIMIT can never hide a target row behind unrelated data.
    private static String uniquePhone(String tail) {
        // 12 digits, distinctive '55' block, nanoTime keeps it unique per run.
        return "+26355" + String.format("%09d", System.nanoTime() % 1_000_000_000L) + tail;
    }

    @Test
    @DisplayName("samples the never-registered backlog (PENDING + PENDING_EXPIRED), excludes everything else")
    void samplesTheRightPopulation() {
        String pending      = uniquePhone("1");
        String agedOut      = uniquePhone("2"); // INACTIVE + PENDING_EXPIRED
        String liveReg      = uniquePhone("3"); // PENDING but registered (live)
        String revokedReg   = uniquePhone("4"); // PENDING but registration revoked
        String active       = uniquePhone("5"); // ACTIVE — not backlog
        String operatorGone = uniquePhone("6"); // INACTIVE + OPERATOR — not recoverable

        UUID tenant = newTenant();
        projection(tenant, pending, LoyaltyUser.Status.PENDING, null);
        projection(tenant, agedOut, LoyaltyUser.Status.INACTIVE, LoyaltyUser.StatusReason.PENDING_EXPIRED);
        projection(tenant, liveReg, LoyaltyUser.Status.PENDING, null);
        registration(liveReg, false);
        projection(tenant, revokedReg, LoyaltyUser.Status.PENDING, null);
        registration(revokedReg, true);
        projection(tenant, active, LoyaltyUser.Status.ACTIVE, null);
        projection(tenant, operatorGone, LoyaltyUser.Status.INACTIVE, LoyaltyUser.StatusReason.OPERATOR);

        List<String> sampled = users.sampleUnregisteredBacklogPhones(1_000_000);

        assertThat(sampled).contains(pending, agedOut);
        // The revocation exclusion is the whole reason for this method — a
        // revoked phone stays out even though its projection is PENDING.
        assertThat(sampled).doesNotContain(revokedReg, liveReg, active, operatorGone);
    }

    @Test
    @DisplayName("DISTINCT collapses a phone with projections under several tenants")
    void distinctAcrossTenants() {
        String phone = uniquePhone("0");
        projection(newTenant(), phone, LoyaltyUser.Status.PENDING, null);
        projection(newTenant(), phone, LoyaltyUser.Status.PENDING, null); // different tenant, same phone

        List<String> sampled = users.sampleUnregisteredBacklogPhones(1_000_000);

        assertThat(sampled).filteredOn(phone::equals).hasSize(1);
    }

    @Test
    @DisplayName("the :batch bind caps the result size")
    void batchLimitBinds() {
        UUID tenant = newTenant();
        for (int i = 0; i < 5; i++) {
            projection(tenant, uniquePhone("b" + i), LoyaltyUser.Status.PENDING, null);
        }

        // At least 5 backlog rows exist (these), so a batch of 3 must cap at 3.
        assertThat(users.sampleUnregisteredBacklogPhones(3)).hasSize(3);
    }
}
