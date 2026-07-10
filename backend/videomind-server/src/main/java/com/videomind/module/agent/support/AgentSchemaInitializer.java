package com.videomind.module.agent.support;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("agent_knowledge_base_id", "VARCHAR(128) NULL");
        columns.put("transcript_version", "INT NOT NULL DEFAULT 0");
        columns.put("agent_ingest_status", "VARCHAR(32) NULL");
        columns.put("summary_status", "VARCHAR(32) NULL");
        columns.put("summary_version", "INT NOT NULL DEFAULT 0");
        columns.put("latest_summary_id", "VARCHAR(128) NULL");
        columns.put("latest_presentation_id", "VARCHAR(128) NULL");
        columns.put("agent_last_error", "VARCHAR(1024) NULL");
        columns.put("agent_updated_at", "DATETIME NULL");
        columns.forEach(this::addColumnIfMissing);
    }

    private void addColumnIfMissing(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'video_file'
                  AND column_name = ?
                """, Integer.class, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE video_file ADD COLUMN " + column + " " + definition);
        }
    }
}
