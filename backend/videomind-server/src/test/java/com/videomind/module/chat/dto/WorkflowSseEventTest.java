package com.videomind.module.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.WorkflowEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowSseEventTest {
    @Test
    void exposesExactlyTheFourPublicFields() throws Exception {
        WorkflowEvent internal = new WorkflowEvent("TOOL", 2, "s1", "VIDEO_TIMELINE_RETRIEVAL",
                "COMPLETED", "工具调用完成", 37L, List.of("ev-1"));

        var tree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(WorkflowSseEvent.from(internal)));

        assertThat(tree.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("phase", "stepId", "status", "message");
        assertThat(tree.has("tool")).isFalse();
        assertThat(tree.has("evidenceIds")).isFalse();
        assertThat(tree.has("elapsedMs")).isFalse();
    }
}
