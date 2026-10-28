package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.controller.SupportTestBase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V63 + {@link SupportRetentionPurgeJob} against real Postgres: support logs go
 * after 12 months and not a day sooner, whoever asks; notes never go.
 */
class SupportRetentionPurgeJobTest extends SupportTestBase {

    @Autowired SupportRetentionPurgeJob job;
    @Autowired LoyaltyMetrics metrics;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID activityRow(String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO support_activity (id, agent_uuid, action, created_at) VALUES (?, 'a', 'SEARCH', now())",
                id);
        age("support_activity", id, age);
        return id;
    }

    private UUID messageRow(String age) {
        UUID id = UUID.randomUUID();
        String phone = randomPhone();
        jdbc.update("""
                INSERT INTO support_message (id, subject_kind, subject_id, recipient_msisdn, recipient_role, kind,
                    channel_requested, delivered_via, outcome, body, agent_uuid, agent_login, created_at, completed_at)
                VALUES (?, 'PHONE', ?, ?, 'CUSTOMER', 'CUSTOM', 'SMS', 'SMS', 'SENT', 'hi', 'a', 'seed@example.com',
                        now(), now())
                """, id, phone, phone);
        age("support_message", id, age);
        return id;
    }

    private UUID noteRow(String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO support_note (id, subject_kind, subject_id, body, agent_uuid, created_at) "
                + "VALUES (?, 'PHONE', ?, 'note', 'a', now())", id, randomPhone());
        age("support_note", id, age);
        return id;
    }

    private void age(String table, UUID id, String age) {
        if (age != null) {
            maintenanceUpdate("UPDATE " + table + " SET created_at = now() - interval '" + age + "' "
                    + "WHERE id = '" + id + "'");
        }
    }

    private boolean exists(String table, UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id) == 1;
    }

    @Test
    @DisplayName("the sweep deletes activity and message rows past 12 months, keeps younger ones, never notes")
    void sweepsPastTwelveMonthsOnly() {
        UUID oldActivity = activityRow("13 months");
        UUID youngActivity = activityRow("11 months");
        UUID oldMessage = messageRow("13 months");
        UUID youngMessage = messageRow("11 months");
        UUID oldNote = noteRow("3 years");

        SupportRetentionPurgeJob.Outcome outcome = job.run();

        assertThat(exists("support_activity", oldActivity)).isFalse();
        assertThat(exists("support_message", oldMessage)).isFalse();
        assertThat(exists("support_activity", youngActivity)).isTrue();
        assertThat(exists("support_message", youngMessage)).isTrue();
        assertThat(exists("support_note", oldNote)).isTrue();
        assertThat(outcome.activity().complete()).isTrue();
        assertThat(outcome.messages().complete()).isTrue();
        assertThat(outcome.activity().rows()).isPositive();
        assertThat(outcome.messages().rows()).isPositive();
    }

    @Test
    @DisplayName("the database refuses to delete a younger row, or any note, whoever asks")
    void theTwelveMonthFloorIsTheDatabases() {
        UUID youngActivity = activityRow("11 months");
        UUID youngMessage = messageRow("11 months");
        UUID oldNote = noteRow("3 years");
        UUID oldActivity = activityRow("13 months");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM support_activity WHERE id = ?", youngActivity))
                .hasMessageContaining("support_activity is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM support_message WHERE id = ?", youngMessage))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM support_note WHERE id = ?", oldNote))
                .hasMessageContaining("support_note is append-only");
        // UPDATE is still refused on an old row; only the delete changed.
        assertThatThrownBy(() -> jdbc.update("UPDATE support_activity SET action = 'X' WHERE id = ?", oldActivity))
                .hasMessageContaining("support_activity is append-only");
        assertThat(jdbc.update("DELETE FROM support_activity WHERE id = ?", oldActivity)).isEqualTo(1);
    }

    @Test
    @DisplayName("a setting below 12 months is raised to 12: an 11-month-old row survives a 1-month setting")
    void aShorterSettingIsRaisedToTheFloor() {
        UUID youngActivity = activityRow("11 months");
        UUID oldActivity = activityRow("13 months");
        SupportRetentionPurgeJob oneMonth = new SupportRetentionPurgeJob(jdbc, metrics,
                new TransactionTemplate(transactionManager), true, 1, 1000, 500);

        SupportRetentionPurgeJob.Outcome outcome = oneMonth.run();

        assertThat(outcome.activity().complete()).isTrue();
        assertThat(exists("support_activity", youngActivity)).isTrue();
        assertThat(exists("support_activity", oldActivity)).isFalse();
    }

    @Test
    @DisplayName("the run is ShedLock-guarded under its own name")
    void theRunIsLocked() throws Exception {
        SchedulerLock lock = SupportRetentionPurgeJob.class.getMethod("purge").getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("supportRetentionPurge");
    }
}
