package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.testsupport.PostgresIntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V64, run the way it runs on a cell: a database at V63 whose tenants share
 * codes the way the portal left them ("ZW" for nearly everyone), then migrated.
 * Done in a database of its own, like {@code VoucherBalanceMigrationIT}.
 *
 * <p>A migration applies once per cell, so every shape it decides is pinned: a
 * shared code becomes a code made from the name, oldest tenant first; a clash
 * with a code another tenant already holds gets a -2; a name with no letters or
 * digits becomes "tenant"; a code no one shares is left exactly as it was,
 * letter case included; and afterwards no two tenants can share a code in any
 * letter case.
 */
class TenantCodeMigrationIT extends PostgresIntegrationTestBase {

    @Test
    void v64_givesSharedCodesOneEach_andThenKeepsThemUnique() throws Exception {
        String db = "v64_codes_" + Long.toHexString(System.nanoTime());
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        }
        String url = POSTGRES.getJdbcUrl().replace("/loyalty_it", "/" + db);

        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("63").load().migrate();

        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID simbisa = tenant(c, "ZW", "Simbisa Brands", "2026-01-01T00:00:00Z");
            UUID fastjet = tenant(c, "ZW", "Fast Jet", "2026-01-02T00:00:00Z");
            UUID simbisaAgain = tenant(c, "zw", "Simbisa  Brands!", "2026-01-03T00:00:00Z");
            UUID symbols = tenant(c, "ZW", "***", "2026-01-04T00:00:00Z");
            UUID holdsTheSlug = tenant(c, "total-energies", "Something Else", "2026-01-05T00:00:00Z");
            UUID total = tenant(c, "ZW", "Total Energies", "2026-01-06T00:00:00Z");
            UUID mwos = tenant(c, "MWOS", "MWOS", "2026-01-07T00:00:00Z");

            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration").target("64").load().migrate();

            assertThat(code(c, simbisa)).isEqualTo("simbisa-brands");
            assertThat(code(c, fastjet)).isEqualTo("fast-jet");
            assertThat(code(c, simbisaAgain)).as("same name, oldest keeps the plain code").isEqualTo("simbisa-brands-2");
            assertThat(code(c, symbols)).isEqualTo("tenant");
            assertThat(code(c, holdsTheSlug)).as("not shared: untouched").isEqualTo("total-energies");
            assertThat(code(c, total)).isEqualTo("total-energies-2");
            assertThat(code(c, mwos)).as("not shared: untouched, letter case kept").isEqualTo("MWOS");
            assertThat(code(c, UUID.fromString("0a571c1c-7c75-4000-a000-000000000001"))).isEqualTo("ticketing");

            assertThatThrownBy(() -> tenant(c, "mwos", "Another", "2026-02-01T00:00:00Z"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_tenants_code_lower");
        }
    }

    private static UUID tenant(Connection c, String code, String name, String createdAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenants (id, code, name, status, created_at) VALUES (?, ?, ?, 'ACTIVE', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setTimestamp(4, Timestamp.from(Instant.parse(createdAt)));
            ps.executeUpdate();
        }
        return id;
    }

    private static String code(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT code FROM tenants WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getString(1);
        }
    }
}
