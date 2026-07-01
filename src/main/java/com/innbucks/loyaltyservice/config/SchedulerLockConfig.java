package com.innbucks.loyaltyservice.config;

import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Enables ShedLock so the service's {@code @Scheduled} jobs are leader-elected
 * across replicas. Without this every pod runs every cron simultaneously, which
 * triples DB load and races on invoice generation.
 *
 * <p>{@code defaultLockAtMostFor = PT10M} is the fail-safe — if a pod dies mid-run
 * its lock is released after 10 minutes so another pod can pick up the next
 * scheduled tick. Each @SchedulerLock annotation can override this for its own
 * job (long-running ones should set lockAtMostFor higher).
 *
 * <p>Storage is the {@code shedlock} table — provisioned by the V6 Flyway
 * migration on the Postgres branch, and by {@link #ensureShedlockTable()}
 * (CREATE TABLE IF NOT EXISTS) on the H2 branch where Flyway is disabled.
 * The IF NOT EXISTS makes it a no-op when Flyway already ran.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockConfig.class);

    private final DataSource dataSource;

    public SchedulerLockConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    public LockProvider lockProvider() {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }

    /**
     * Safety net for the H2 branch where Flyway is disabled and Hibernate's
     * ddl-auto only manages JPA entity tables. The shedlock table isn't a JPA
     * entity (ShedLock owns it directly via JDBC), so neither pathway creates
     * it there. This idempotent CREATE runs once on startup and is a no-op on
     * the Postgres branch where the V6 migration already provisioned the
     * table.
     */
    @PostConstruct
    void ensureShedlockTable() {
        try {
            new JdbcTemplate(dataSource).execute("""
                    CREATE TABLE IF NOT EXISTS shedlock (
                        name VARCHAR(64) PRIMARY KEY,
                        lock_until TIMESTAMP NOT NULL,
                        locked_at TIMESTAMP NOT NULL,
                        locked_by VARCHAR(255) NOT NULL
                    )
                    """);
            log.debug("shedlock table verified / created");
        } catch (Exception e) {
            // Don't take the service down for this — if the table is unreachable
            // ShedLock will surface the same error at first lock attempt, with
            // its own diagnostics.
            log.warn("Could not ensure shedlock table exists: {}", e.getMessage());
        }
    }
}

