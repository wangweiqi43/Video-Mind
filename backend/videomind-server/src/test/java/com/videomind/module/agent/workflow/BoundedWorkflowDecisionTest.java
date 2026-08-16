package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Route;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import java.util.List;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedWorkflowDecisionTest {
    private final StructuredLlmAgentPlanner structuredPlanner = mock(StructuredLlmAgentPlanner.class);
    private final EvidenceAgentCritic evidenceCritic = mock(EvidenceAgentCritic.class);
    private final StructuredLlmAgentCritic structuredCritic = mock(StructuredLlmAgentCritic.class);
    private final AgentWorkflowProperties properties = new AgentWorkflowProperties();
    private final WorkflowDecisionRunner decisions = new WorkflowDecisionRunner();
    private final Request request = new Request(7L, 51L, List.of(11L), "q");
    private final Plan plan = new Plan(Route.VIDEO_RAG, List.of(), 0);

    @Test
    void everyRequestUsesTheStructuredPlannerByDefault() {
        when(structuredPlanner.plan(request, null, null, 0, properties.getMaxToolCalls()))
                .thenReturn(plan);
        BoundedLlmAgentPlanner planner = new BoundedLlmAgentPlanner(
                structuredPlanner, properties, decisions);

        assertThat(planner.plan(request, null, null, 0)).isSameAs(plan);
        verify(structuredPlanner).plan(request, null, null, 0, properties.getMaxToolCalls());
    }

    @Test
    void invalidPlannerOutputFailsClosedWithoutRuleFallback() {
        when(structuredPlanner.plan(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenThrow(new IllegalArgumentException("invalid json"));
        BoundedLlmAgentPlanner planner = new BoundedLlmAgentPlanner(
                structuredPlanner, properties, decisions);

        assertThatThrownBy(() -> planner.plan(request, null, null, 0))
                .isInstanceOf(WorkflowDecisionUnavailableException.class);
    }

    @Test
    void structuralEvidenceFailureStopsBeforeTheLlmCritic() {
        Critique invalid = new Critique(Verdict.FAIL, "invalid structure");
        when(evidenceCritic.review(request, plan, List.of(), 0, Long.MAX_VALUE)).thenReturn(invalid);
        BoundedLlmAgentCritic critic = new BoundedLlmAgentCritic(
                evidenceCritic, structuredCritic, properties, decisions);

        assertThat(critic.review(request, plan, List.of(), 0)).isSameAs(invalid);
        verify(structuredCritic, never()).review(any(), any(), any(), any(Integer.class));
    }

    @Test
    void invalidSemanticCritiqueFailsClosedWithoutAcceptingEvidence() {
        when(evidenceCritic.review(any(), any(), any(), any(Integer.class), anyLong()))
                .thenReturn(new Critique(Verdict.ACCEPT, "structure valid"));
        when(structuredCritic.review(request, plan, List.of(), 0))
                .thenThrow(new IllegalArgumentException("invalid json"));
        BoundedLlmAgentCritic critic = new BoundedLlmAgentCritic(
                evidenceCritic, structuredCritic, properties, decisions);

        assertThatThrownBy(() -> critic.review(request, plan, List.of(), 0))
                .isInstanceOf(WorkflowDecisionUnavailableException.class);
    }

    @Test
    void usesTheThirtyFortyThirtyWorkflowBudget() {
        BoundedLlmAgentPlanner planner = new BoundedLlmAgentPlanner(
                structuredPlanner, properties, decisions);
        BoundedLlmAgentCritic critic = new BoundedLlmAgentCritic(
                evidenceCritic, structuredCritic, properties, decisions);

        assertThat(request.deadline()).isEqualTo(Duration.ofSeconds(100));
        assertThat(planner.decisionTimeout()).isEqualTo(30_000);
        assertThat(properties.getExecutorTimeoutMillis()).isEqualTo(40_000);
        assertThat(critic.decisionTimeout()).isEqualTo(30_000);
    }

    @Test
    void configurationCannotRaiseThePlannerOrCriticHardCaps() {
        properties.setPlannerTimeoutMillis(90_000);
        properties.setCriticTimeoutMillis(90_000);
        BoundedLlmAgentPlanner planner = new BoundedLlmAgentPlanner(
                structuredPlanner, properties, decisions);
        BoundedLlmAgentCritic critic = new BoundedLlmAgentCritic(
                evidenceCritic, structuredCritic, properties, decisions);

        assertThat(planner.decisionTimeout()).isEqualTo(30_000);
        assertThat(critic.decisionTimeout()).isEqualTo(30_000);
    }
}
