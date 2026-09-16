package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
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

    private void projection(String phone, LoyaltyUser.Status status, LoyaltyUser.StatusReason reason) {
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(UUID.randomUUID());
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

    @Test
    @DisplayName("samples exactly the never-registered backlog: PENDING + PENDING_EXPIRED, and nothing else")
    void samplesTheRightPopulation() {
        String pending      = "+263771000001";
        String agedOut      = "+263771000002"; // INACTIVE + PENDING_EXPIRED
        String liveReg      = "+263771000003"; // PENDING but registered (live)
        String revokedReg   = "+263771000004"; // PENDING but registration revoked
        String active       = "+263771000005"; // ACTIVE — not backlog
        String operatorGone = "+263771000006"; // INACTIVE + OPERATOR — not recoverable

        projection(pending, LoyaltyUser.Status.PENDING, null);
        projection(agedOut, LoyaltyUser.Status.INACTIVE, LoyaltyUser.StatusReason.PENDING_EXPIRED);
        projection(liveReg, LoyaltyUser.Status.PENDING, null);
        registration(liveReg, false);
        projection(revokedReg, LoyaltyUser.Status.PENDING, null);
        registration(revokedReg, true);
        projection(active, LoyaltyUser.Status.ACTIVE, null);
        projection(operatorGone, LoyaltyUser.Status.INACTIVE, LoyaltyUser.StatusReason.OPERATOR);

        List<String> sampled = users.sampleUnregisteredBacklogPhones(100);

        assertThat(sampled).containsExactlyInAnyOrder(pending, agedOut);
        // The revocation exclusion is the whole reason for this method — a
        // revoked phone stays out even though its projection is PENDING.
        assertThat(sampled).doesNotContain(revokedReg, liveReg, active, operatorGone);
    }

    @Test
    @DisplayName("DISTINCT collapses a phone with projections under several tenants")
    void distinctAcrossTenants() {
        String phone = "+263771000010";
        projection(phone, LoyaltyUser.Status.PENDING, null);
        projection(phone, LoyaltyUser.Status.PENDING, null); // different tenant, same phone

        List<String> sampled = users.sampleUnregisteredBacklogPhones(100);

        assertThat(sampled).filteredOn(phone::equals).hasSize(1);
    }

    @Test
    @DisplayName("the :batch bind caps the result size")
    void batchLimitBinds() {
        for (int i = 0; i < 5; i++) {
            projection("+26377100002" + i, LoyaltyUser.Status.PENDING, null);
        }

        assertThat(users.sampleUnregisteredBacklogPhones(3)).hasSize(3);
    }
}
