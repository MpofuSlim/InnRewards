package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TenantMember;
import com.innbucks.loyaltyservice.repository.TenantMemberRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent startup backfill: for every tenant with a non-blank
 * {@code ownerEmail} that isn't already a member, insert a {@link TenantMember}
 * row. Lets the H2 / ddl-auto deployment pick up the new membership model
 * without losing access for existing owners (the Postgres branch handles the
 * same backfill via Flyway V2 migration). Skips silently if all owners are
 * already members, so safe to leave running on every boot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantMemberBackfill implements ApplicationRunner {

    private final TenantRepository tenants;
    private final TenantMemberRepository members;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int added = 0;
        for (Tenant tenant : tenants.findAll()) {
            String owner = tenant.getOwnerEmail();
            if (owner == null || owner.isBlank()) continue;
            if (members.existsByTenantIdAndEmail(tenant.getId(), owner)) continue;
            TenantMember m = new TenantMember();
            m.setTenantId(tenant.getId());
            m.setEmail(owner);
            members.save(m);
            added++;
        }
        if (added > 0) {
            log.info("Tenant membership backfill complete added={}", added);
        }
    }
}
