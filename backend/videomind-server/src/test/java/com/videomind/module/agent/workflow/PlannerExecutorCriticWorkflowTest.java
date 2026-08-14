package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Status;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerExecutorCriticWorkflowTest {
    private final AgentPlanner planner = mock(AgentPlanner.class);
    private final AgentExecutor executor = mock(AgentExecutor.class);
    private final AgentCritic critic = mock(AgentCritic.class);
    private final PlannerExecutorCriticWorkflow workflow = new PlannerExecutorCriticWorkflow(planner, executor, critic);
    private final Request request = new Request(7L, List.of(11L), "解释事务消息", 4, Duration.ofSeconds(5));
    private final Plan plan = new Plan("HYBRID_RAG", List.of(new Step("s1", "HYBRID_RETRIEVAL", "query")), 0);

    @Test
    void acceptsEvidenceOnFirstPlan() {
        when(planner.plan(any(), any(), any(), any(Integer.class))).thenReturn(plan);
        Evidence evidence = new Evidence("ev-1", 11L, 1L, 1L, 0, 0, "title", "heading",
                "content", "parent", 0L, 1_000L, 0.1, 0.9, 0.8);
        when(executor.execute(any(), any())).thenReturn(new StepResult("s1", List.of(evidence)));
        when(critic.review(any(), any(), any(), any(Integer.class)))
                .thenReturn(new Critique(Verdict.ACCEPT, "ok"));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.evidence()).containsExactly(evidence);
    }

    @Test
    void permitsOnlyOneReplanThenFailsClosed() {
        when(planner.plan(any(), any(), any(), any(Integer.class))).thenReturn(plan,
                new Plan("EXPANDED", plan.steps(), 1));
        when(executor.execute(any(), any())).thenReturn(new StepResult("s1", List.of()));
        when(critic.review(any(), any(), any(), any(Integer.class)))
                .thenReturn(new Critique(Verdict.REPLAN, "expand"), new Critique(Verdict.FAIL, "none"));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(result.replans()).isEqualTo(1);
        verify(executor, times(2)).execute(any(), any());
    }

    @Test
    void stopsBeforeExceedingToolBudget() {
        Plan twoSteps = new Plan("MULTI", List.of(new Step("s1", "tool", "a"),
                new Step("s2", "tool", "b")), 0);
        when(planner.plan(any(), any(), any(), any(Integer.class))).thenReturn(twoSteps);
        when(executor.execute(any(), any())).thenReturn(new StepResult("s1", List.of()));

        var result = workflow.run(new Request(7L, List.of(11L), "q", 1, Duration.ofSeconds(5)));

        assertThat(result.status()).isEqualTo(Status.TOOL_BUDGET_EXCEEDED);
        verify(executor, times(1)).execute(any(), any());
    }
}
