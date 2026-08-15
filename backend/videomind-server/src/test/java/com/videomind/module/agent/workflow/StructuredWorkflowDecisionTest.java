package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import org.junit.jupiter.api.Test;

class StructuredWorkflowDecisionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredLlmAgentPlanner planner = new StructuredLlmAgentPlanner(
            mock(WorkflowDecisionClient.class), mapper);
    private final StructuredLlmAgentCritic critic = new StructuredLlmAgentCritic(
            mock(WorkflowDecisionClient.class), mapper);

    @Test
    void parsesStrictPlannerJsonAndRejectsUnknownTools() {
        var plan = planner.parse("""
                ```json
                {"route":"DEEP_RETRIEVAL","steps":[{"id":"s1","tool":"VIDEO_TIMELINE_RETRIEVAL","input":"时间轴证据"}]}
                ```
                """, 1, 6);

        assertThat(plan.generation()).isEqualTo(1);
        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.id()).isEqualTo("s1");
            assertThat(step.tool()).isEqualTo("VIDEO_TIMELINE_RETRIEVAL");
        });
        assertThatThrownBy(() -> planner.parse(
                "{\"route\":\"x\",\"steps\":[{\"id\":\"s1\",\"tool\":\"WEB_SEARCH\",\"input\":\"q\"}]}",
                0, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void criticCannotAcceptWithoutEvidence() {
        assertThatThrownBy(() -> critic.parse(
                "{\"verdict\":\"ACCEPT\",\"reason\":\"看起来可以\"}", 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(critic.parse("{\"verdict\":\"REPLAN\",\"reason\":\"改写查询\"}", 0).verdict())
                .isEqualTo(Verdict.REPLAN);
    }
}
