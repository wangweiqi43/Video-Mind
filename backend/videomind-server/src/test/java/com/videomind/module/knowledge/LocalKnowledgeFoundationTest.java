package com.videomind.module.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.common.enums.ProcessingTaskState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LocalKnowledgeFoundationTest {

    @Test
    void migrationDefinesEveryAuthoritativeStore() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V13__local_knowledge_workflow_foundation.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                    "CREATE TABLE knowledge_base",
                    "CREATE TABLE knowledge_document",
                    "CREATE TABLE document_version",
                    "CREATE TABLE document_chunk",
                    "CREATE TABLE processing_task",
                    "CREATE TABLE mq_transaction_event",
                    "CREATE TABLE mq_consume_record",
                    "CREATE TABLE video_timeline",
                    "CREATE TABLE conversation_knowledge_base",
                    "CREATE TABLE chat_generation");
        }
    }

    @Test
    void taskTerminalStatesAreExplicit() {
        assertThat(ProcessingTaskState.SUCCESS.terminal()).isTrue();
        assertThat(ProcessingTaskState.FAILED.terminal()).isTrue();
        assertThat(ProcessingTaskState.DEAD.terminal()).isTrue();
        assertThat(ProcessingTaskState.RETRY_WAIT.terminal()).isFalse();
    }
}
