package com.videomind.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ConfiguredFlywayMigrationTest {

    @Test
    void migratesAnExplicitlyConfiguredLocalDatabase() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MIGRATION_LIVE_ENABLED")));
        String url = required("MIGRATION_LIVE_JDBC_URL");
        String username = required("MIGRATION_LIVE_USERNAME");
        String password = required("MIGRATION_LIVE_PASSWORD");

        Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            try (var history = statement.executeQuery("SELECT version,success FROM flyway_schema_history "
                    + "ORDER BY installed_rank DESC LIMIT 1")) {
                assertThat(history.next()).isTrue();
                assertThat(history.getString(1)).isEqualTo("21");
                assertThat(history.getBoolean(2)).isTrue();
            }
            try (var uniqueIndex = statement.executeQuery("SELECT COUNT(*) FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='video_file' "
                    + "AND INDEX_NAME='uk_video_file_user_md5' AND NON_UNIQUE=0")) {
                uniqueIndex.next();
                assertThat(uniqueIndex.getLong(1)).isEqualTo(2);
            }
            try (var legacyTables = statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('mindagent_binding','video_agent_task')")) {
                legacyTables.next();
                assertThat(legacyTables.getLong(1)).isZero();
            }
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }
}
