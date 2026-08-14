package com.videomind.module.knowledge.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PhysicalDeletionRepositoryIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private PhysicalDeletionRepository repository;
    private String database;

    @BeforeEach
    void createTemporaryDatabase() {
        assumeTrue(Boolean.parseBoolean(System.getenv("VIDEOMIND_DELETION_MYSQL_INTEGRATION"))
                        || Boolean.getBoolean("VIDEOMIND_DELETION_MYSQL_INTEGRATION"),
                "set VIDEOMIND_DELETION_MYSQL_INTEGRATION=true for isolated MySQL deletion tests");
        String host = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("MYSQL_PORT", "3307");
        String username = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
        String password = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://" + host + ":" + port + "/?useSSL=false&allowPublicKeyRetrieval=true",
                username, password));
        database = "videomind_delete_it_" + Long.toUnsignedString(System.nanoTime(), 36);
        assertThat(database).matches("videomind_delete_it_[a-z0-9]+");
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=false&allowPublicKeyRetrieval=true",
                username, password));
        createSchema();
        repository = new PhysicalDeletionRepository(jdbc);
    }

    @AfterEach
    void dropTemporaryDatabase() {
        if (admin != null && database != null && database.matches("videomind_delete_it_[a-z0-9]+")) {
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void physicallyDeletesKnowledgeTreeTwiceButKeepsFixedConversationHistory() {
        jdbc.update("INSERT INTO knowledge_base VALUES (11,7,'USER',NULL,0)");
        jdbc.update("INSERT INTO knowledge_document VALUES (21,11,7,0)");
        jdbc.update("INSERT INTO document_version VALUES (31,21,'docs','source.pdf','docs','parsed.md')");
        jdbc.update("INSERT INTO document_asset VALUES (41,31,'docs','image.png')");
        jdbc.update("INSERT INTO document_chunk VALUES (51,11,7)");
        jdbc.update("INSERT INTO chat_session VALUES (61,7,15,0,JSON_ARRAY(10,11))");
        jdbc.update("INSERT INTO conversation_knowledge_base VALUES (61,11)");

        DeletionManifest manifest = repository.knowledgeManifest(7L, 11L);
        assertThat(manifest.documentIds()).containsExactly(21L);
        assertThat(manifest.objects()).extracting(DeletionManifest.ObjectRef::objectKey)
                .containsExactlyInAnyOrder("source.pdf", "parsed.md", "image.png");
        assertThat(manifest.conversationIds()).containsExactly(61L);

        repository.deleteKnowledgeRows(manifest);
        repository.deleteKnowledgeRows(manifest);

        assertThat(count("knowledge_base")).isZero();
        assertThat(count("knowledge_document")).isZero();
        assertThat(count("document_version")).isZero();
        assertThat(count("document_asset")).isZero();
        assertThat(count("document_chunk")).isZero();
        assertThat(count("conversation_knowledge_base")).isZero();
        assertThat(count("chat_session")).isEqualTo(1);
    }

    @Test
    void physicallyDeletesVideoKnowledgeAssetsAuditsAndRootTwice() {
        jdbc.update("INSERT INTO video_file VALUES (15,7,0,'videos','video.mp4')");
        jdbc.update("INSERT INTO knowledge_base VALUES (11,7,'VIDEO',15,0)");
        jdbc.update("INSERT INTO knowledge_document VALUES (21,11,7,0)");
        jdbc.update("INSERT INTO document_version VALUES (31,21,'timeline','timeline.md','timeline','timeline.md')");
        jdbc.update("INSERT INTO document_asset VALUES (41,31,'timeline','frame.png')");
        jdbc.update("INSERT INTO document_chunk VALUES (51,11,7)");
        jdbc.update("INSERT INTO video_timeline VALUES (15,7,'timeline','timeline.md','events.json')");
        jdbc.update("INSERT INTO video_report VALUES (15,7,'reports','report.md','report.json')");
        jdbc.update("INSERT INTO video_upload_session VALUES (15,7,'upload-1')");
        jdbc.update("INSERT INTO chat_session VALUES (61,7,15,0,JSON_ARRAY(11))");
        jdbc.update("INSERT INTO chat_message VALUES (61,7)");
        jdbc.update("INSERT INTO conversation_summary VALUES (61)");
        jdbc.update("INSERT INTO conversation_knowledge_base VALUES (61,11)");
        jdbc.update("INSERT INTO chat_generation VALUES (71,61)");
        jdbc.update("INSERT INTO agent_execution VALUES (81,71)");
        jdbc.update("INSERT INTO agent_step VALUES (81)");
        for (String table : List.of("video_asr_segment", "video_ocr_observation", "video_transcription",
                "ai_summary_result", "task_record")) {
            jdbc.update("INSERT INTO " + table + " VALUES (15,7)");
        }

        DeletionManifest manifest = repository.videoManifest(7L, 15L);
        assertThat(manifest.uploadIds()).containsExactly("upload-1");
        assertThat(manifest.conversationIds()).containsExactly(61L);
        assertThat(manifest.objects()).extracting(DeletionManifest.ObjectRef::objectKey)
                .contains("video.mp4", "timeline.md", "events.json", "report.md", "report.json", "frame.png");

        repository.deleteVideoRows(manifest);
        repository.deleteVideoRows(manifest);

        for (String table : List.of("video_file", "knowledge_base", "knowledge_document", "document_version",
                "document_asset", "document_chunk", "video_timeline", "video_report", "video_upload_session",
                "chat_session", "chat_message", "conversation_summary", "conversation_knowledge_base",
                "chat_generation", "agent_execution", "agent_step", "video_asr_segment",
                "video_ocr_observation", "video_transcription", "ai_summary_result", "task_record")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private void createSchema() {
        for (String ddl : List.of(
                "CREATE TABLE knowledge_base(id BIGINT,user_id BIGINT,type VARCHAR(16),video_id BIGINT,deleted INT)",
                "CREATE TABLE knowledge_document(id BIGINT,knowledge_base_id BIGINT,user_id BIGINT,deleted INT)",
                "CREATE TABLE document_version(id BIGINT,document_id BIGINT,original_bucket VARCHAR(128),original_object_key VARCHAR(768),markdown_bucket VARCHAR(128),markdown_object_key VARCHAR(768))",
                "CREATE TABLE document_asset(id BIGINT,document_version_id BIGINT,bucket VARCHAR(128),object_key VARCHAR(768))",
                "CREATE TABLE document_chunk(id BIGINT,knowledge_base_id BIGINT,user_id BIGINT)",
                "CREATE TABLE chat_session(id BIGINT,user_id BIGINT,video_id BIGINT,deleted INT,knowledge_base_ids_json JSON)",
                "CREATE TABLE conversation_knowledge_base(conversation_id BIGINT,knowledge_base_id BIGINT)",
                "CREATE TABLE video_file(id BIGINT,user_id BIGINT,deleted INT,minio_bucket VARCHAR(128),minio_object_key VARCHAR(768))",
                "CREATE TABLE video_timeline(video_id BIGINT,user_id BIGINT,bucket VARCHAR(128),markdown_object_key VARCHAR(768),event_json_object_key VARCHAR(768))",
                "CREATE TABLE video_report(video_id BIGINT,user_id BIGINT,bucket VARCHAR(128),markdown_object_key VARCHAR(768),json_object_key VARCHAR(768))",
                "CREATE TABLE video_upload_session(video_id BIGINT,user_id BIGINT,upload_id VARCHAR(128))",
                "CREATE TABLE chat_message(session_id BIGINT,user_id BIGINT)",
                "CREATE TABLE conversation_summary(conversation_id BIGINT)",
                "CREATE TABLE chat_generation(id BIGINT,conversation_id BIGINT)",
                "CREATE TABLE agent_execution(id BIGINT,generation_id BIGINT)",
                "CREATE TABLE agent_step(execution_id BIGINT)",
                "CREATE TABLE video_asr_segment(video_id BIGINT,user_id BIGINT)",
                "CREATE TABLE video_ocr_observation(video_id BIGINT,user_id BIGINT)",
                "CREATE TABLE video_transcription(video_id BIGINT,user_id BIGINT)",
                "CREATE TABLE ai_summary_result(video_id BIGINT,user_id BIGINT)",
                "CREATE TABLE task_record(video_id BIGINT,user_id BIGINT)")) {
            jdbc.execute(ddl);
        }
    }
}
