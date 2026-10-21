package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V61's backfill, run the way it runs on a cell: a database migrated to V60,
 * holding the history V60 could hold, then migrated to V61. Done in a database
 * of its own (same container), so the shared schema every other IT uses never
 * sees a half-migrated state and this test cannot be confused by their rows.
 *
 * <p>A migration applies ONCE per cell; a backfill that is wrong is not a bug a
 * later edit can fix, which is why each shape of row it decides is pinned here:
 * a live money voucher keeps its whole face value as its balance, a spent one
 * (REDEEMED, or revoked after its last use) holds zero, a legacy non-money row
 * and a row with no face value keep no balance, the SUCCESS history gets the
 * money each use moved and exactly one first use per voucher, and refusals are
 * left alone.
 */
class VoucherBalanceMigrationIT extends PostgresIntegrationTestBase {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();

    @Test
    void v61_backfillsTheBalanceAndTheRedemptionHistory() throws Exception {
        String db = "v61_backfill_" + Long.toHexString(System.nanoTime());
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        }
        String url = POSTGRES.getJdbcUrl().replace("/loyalty_it", "/" + db);

        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("60").load().migrate();

        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            exec(c, "INSERT INTO tenants (id, code, name, status) VALUES (?, 'mig', 'Migration', 'ACTIVE')", TENANT);

            UUID live = voucher(c, "ISSUED", 1, "50.0000", "50.0000", null);
            UUID expired = voucher(c, "EXPIRED", 1, "20.0000", "20.0000", null);
            UUID oldAmount = voucher(c, "VIEWED", 1, "7.5000", "7.5000", "AMOUNT");
            UUID spent = voucher(c, "REDEEMED", 0, "5.0000", "4.5000", null);
            UUID revokedAfterUse = voucher(c, "REVOKED", 0, "8.0000", "8.0000", null);
            UUID revokedUnused = voucher(c, "REVOKED", 1, "9.0000", "9.0000", null);
            UUID percent = voucher(c, "ISSUED", 1, "10.0000", null, "PERCENT");
            UUID noFace = voucher(c, "ISSUED", 1, null, null, null);

            UUID rejectedFirst = redemption(c, spent, "REJECTED", "2026-01-01T09:00:00Z");
            UUID firstUse = redemption(c, spent, "SUCCESS", "2026-01-02T09:00:00Z");
            UUID secondUse = redemption(c, spent, "SUCCESS", "2026-01-03T09:00:00Z");
            UUID onlyUse = redemption(c, revokedAfterUse, "SUCCESS", "2026-01-04T09:00:00Z");

            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration").target("61").load().migrate();

            assertThat(balance(c, live)).isEqualByComparingTo("50");
            assertThat(balance(c, expired)).as("history: what it held when it lapsed").isEqualByComparingTo("20");
            assertThat(balance(c, oldAmount)).isEqualByComparingTo("7.5");
            assertThat(balance(c, spent)).isEqualByComparingTo("0");
            assertThat(balance(c, revokedAfterUse)).isEqualByComparingTo("0");
            assertThat(balance(c, revokedUnused)).isEqualByComparingTo("9");
            assertThat(balance(c, percent)).as("10% off is not 10 of anything").isNull();
            assertThat(balance(c, noFace)).isNull();

            assertUse(c, firstUse, "5", "4.5", true);
            assertUse(c, secondUse, "5", "4.5", false);
            assertUse(c, onlyUse, "8", "8", true);
            assertUse(c, rejectedFirst, null, null, false);

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM voucher_redemptions WHERE balance_after IS NOT NULL")) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getInt(1)).as("never recorded, so never guessed").isZero();
            }
        }
    }

    private static UUID voucher(Connection c, String status, int uses, String face, String base, String valueType)
            throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO vouchers (id, tenant_id, merchant_id, code, signature, status, uses_remaining, "
                        + "face_value, base_value, currency, value_type) VALUES (?, ?, ?, ?, 'sig', ?, ?, ?, ?, 'USD', ?)",
                id, TENANT, MERCHANT, id.toString().substring(0, 16), status, uses,
                face == null ? null : new BigDecimal(face), base == null ? null : new BigDecimal(base), valueType);
        return id;
    }

    private static UUID redemption(Connection c, UUID voucher, String result, String at) throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO voucher_redemptions (id, tenant_id, voucher_id, merchant_id, result, redeemed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, TENANT, voucher, MERCHANT, result,
                Timestamp.from(Instant.parse(at)));
        return id;
    }

    private static BigDecimal balance(Connection c, UUID voucher) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT remaining_balance FROM vouchers WHERE id = ?")) {
            ps.setObject(1, voucher);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private static void assertUse(Connection c, UUID id, String amount, String base, boolean first) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT amount, base_amount, first_use FROM voucher_redemptions WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (amount == null) {
                assertThat(rs.getBigDecimal(1)).isNull();
                assertThat(rs.getBigDecimal(2)).isNull();
            } else {
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(amount);
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo(base);
            }
            assertThat(rs.getBoolean(3)).isEqualTo(first);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
