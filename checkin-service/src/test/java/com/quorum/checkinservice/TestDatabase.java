package com.quorum.checkinservice;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Test contexts that share the in-memory H2 database see each other's committed rows,
 * so tests that count rows start from empty tables.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    public static void wipe(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
                String.class);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        tables.forEach(t -> jdbc.execute("TRUNCATE TABLE \"" + t + "\""));
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }
}
