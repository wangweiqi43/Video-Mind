package com.videomind.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LegacySchemaMigrationTest {

    private static final String FRESH_DB = "videomind_migration_v17_fresh";
    private static final String UPGRADE_DB = "videomind_migration_v17_upgrade";
    private static String host;
    private static String port;
    private static String username;
    private static String password;

    @BeforeAll
    static void prepareDatabases() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MIGRATION_TEST_ENABLED")));
        host = env("MIGRATION_TEST_MYSQL_HOST", "127.0.0.1");
        port = env("MIGRATION_TEST_MYSQL_PORT", "3317");
        username = env("MIGRATION_TEST_MYSQL_USERNAME", "root");
        password = env("MIGRATION_TEST_MYSQL_PASSWORD", "migration-root");
        recreate(FRESH_DB);
        recreate(UPGRADE_DB);
    }

    @AfterAll
    static void removeDatabases() throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("MIGRATION_TEST_ENABLED"))) return;
        drop(FRESH_DB);
        drop(UPGRADE_DB);
    }

    @Test
    void migratesFreshDatabaseWithoutLegacyAgentSchema() throws Exception {
        migrate(FRESH_DB, null);

        assertLegacySchemaRemoved(FRESH_DB);
        assertLocalSchemaRetained(FRESH_DB);
    }

    @Test
    void upgradesV15DataAndRetainsOnlyLocalFields() throws Exception {
        migrate(UPGRADE_DB, "15");
        try (Connection connection = connect(UPGRADE_DB); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO video_file "
                    + "(id,user_id,original_filename,file_md5,file_size,upload_status,transcript_version,"
                    + "summary_status,summary_version,latest_summary_id,agent_source_knowledge_base_id,"
                    + "agent_report_knowledge_base_id,agent_ingest_status,agent_report_status) VALUES "
                    + "(1,7,'legacy.mp4','00000000000000000000000000000000',1024,'UPLOADED',3,"
                    + "'SUCCESS',3,'summary-3','remote-source','remote-report','SUCCESS','SUCCESS')");
            statement.executeUpdate("INSERT INTO chat_session "
                    + "(id,user_id,video_id,title,application_mode,mindagent_conversation_id) "
                    + "VALUES (1,7,1,'legacy','LOCAL','remote-conversation')");
            statement.executeUpdate("INSERT INTO mindagent_binding "
                    + "(user_id,mindagent_subject,access_token_cipher,refresh_token_cipher,scopes,access_expires_at) "
                    + "VALUES (7,'subject','access','refresh','chat',NOW())");
            statement.executeUpdate("INSERT INTO video_agent_task "
                    + "(video_id,user_id,agent_task_id,task_type,status) "
                    + "VALUES (1,7,'remote-task','INGEST','SUCCESS')");
        }

        migrate(UPGRADE_DB, null);

        assertLegacySchemaRemoved(UPGRADE_DB);
        assertLocalSchemaRetained(UPGRADE_DB);
        try (Connection connection = connect(UPGRADE_DB); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT transcript_version,summary_status,"
                     + "summary_version,latest_summary_id FROM video_file WHERE id=1")) {
            assertThat(result.next()).isTrue();
            assertThat(List.of(result.getInt(1), result.getString(2), result.getInt(3), result.getString(4)))
                    .containsExactly(3, "SUCCESS", 3, "summary-3");
        }
    }

    private static void assertLegacySchemaRemoved(String database) throws Exception {
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('mindagent_binding','video_agent_task')"))
                    .isZero();
            assertThat(count(statement, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND ((TABLE_NAME='chat_session' AND COLUMN_NAME IN ('application_mode','mindagent_conversation_id')) "
                    + "OR (TABLE_NAME='video_file' AND (COLUMN_NAME LIKE 'agent_%' "
                    + "OR COLUMN_NAME='latest_presentation_id')))"))
                    .isZero();
        }
    }

    private static void assertLocalSchemaRetained(String database) throws Exception {
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_NAME='video_file' AND COLUMN_NAME IN "
                    + "('transcript_version','summary_status','summary_version','latest_summary_id')"))
                    .isEqualTo(4);
            assertThat(count(statement, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_NAME IN ('app_user','user_refresh_token','agent_execution','agent_step')"))
                    .isEqualTo(4);
        }
    }

    private static long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void migrate(String database, String target) {
        var configuration = Flyway.configure().dataSource(url(database), username, password)
                .locations("classpath:db/migration")
                .validateOnMigrate(true);
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private static void recreate(String database) throws Exception {
        drop(database);
        try (Connection connection = DriverManager.getConnection(adminUrl(), username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + database
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static void drop(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(adminUrl(), username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database);
        }
    }

    private static Connection connect(String database) throws Exception {
        return DriverManager.getConnection(url(database), username, password);
    }

    private static String adminUrl() {
        return "jdbc:mysql://" + host + ":" + port
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private static String url(String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
