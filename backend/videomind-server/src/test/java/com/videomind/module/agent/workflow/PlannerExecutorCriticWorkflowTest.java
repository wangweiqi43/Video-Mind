package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Mode;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Status;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlannerExecutorCriticWorkflowTest {
    private final AgentPlanner planner = mock(AgentPlanner.class);
    private final AgentExecutor executor = mock(AgentExecutor.class);
    private final AgentCritic critic = mock(AgentCritic.class);
    private final WorkflowClock clock = mock(WorkflowClock.class);
    private final PlannerExecutorCriticWorkflow workflow = new PlannerExecutorCriticWorkflow(
            planner, executor, critic, clock);
    private final Request request = new Request(7L, List.of(11L), "解释事务消息", Mode.STANDARD);
    private final Plan plan = new Plan("HYBRID_RAG", List.of(new Step("s1", "HYBRID_RETRIEVAL", "query")), 0);

    PlannerExecutorCriticWorkflowTest() {
        when(clock.now()).thenReturn(Instant.parse("2026-08-15T00:00:00Z"));
    }

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
        Plan threeSteps = new Plan("MULTI", List.of(new Step("s1", "tool", "a"),
                new Step("s2", "tool", "b"), new Step("s3", "tool", "c")), 0);
        when(planner.plan(any(), any(), any(), any(Integer.class))).thenReturn(threeSteps);
        when(executor.execute(any(), any())).thenReturn(new StepResult("s1", List.of()));

        var result = workflow.run(new Request(7L, List.of(11L), "q", Mode.STANDARD));

        assertThat(result.status()).isEqualTo(Status.TOOL_BUDGET_EXCEEDED);
        verify(executor, times(2)).execute(any(), any());
    }

    @Test
    void deepProfileAllowsTwoReplansAndSixToolCalls() {
        when(planner.plan(any(), any(), any(), any(Integer.class))).thenReturn(plan,
                new Plan("R1", plan.steps(), 1), new Plan("R2", plan.steps(), 2));
        when(executor.execute(any(), any())).thenReturn(new StepResult("s1", List.of()));
        when(critic.review(any(), any(), any(), any(Integer.class)))
                .thenReturn(new Critique(Verdict.REPLAN, "r1"), new Critique(Verdict.REPLAN, "r2"),
                        new Critique(Verdict.FAIL, "done"));

        Request deep = new Request(7L, List.of(11L), "q", Mode.DEEP);
        var result = workflow.run(deep);

        assertThat(deep.maxToolCalls()).isEqualTo(6);
        assertThat(deep.maxReplans()).isEqualTo(2);
        assertThat(deep.deadline()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(result.replans()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(3);
    }

    @Test
    void deadlineIsCheckedAgainAfterACompletedToolCall() {
        Instant start = Instant.parse("2026-08-15T00:00:00Z");
        when(clock.now()).thenReturn(start, start, start, start,
                start.plusSeconds(20));
        when(planner.plan(any(), any(), any(), any(Integer.class))).thenReturn(plan);
        when(executor.execute(any(), any())).thenReturn(new StepResult("s1", List.of()));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.DEADLINE_EXCEEDED);
        assertThat(result.toolCalls()).isEqualTo(1);
        verify(critic, never()).review(any(), any(), any(), any(Integer.class));
    }
}
