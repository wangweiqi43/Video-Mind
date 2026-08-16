package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.QueryOrigin;
import com.videomind.module.agent.workflow.AgentWorkflowModels.QuerySet;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Route;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Status;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlannerExecutorCriticWorkflowTest {
    private final AgentPlanner planner = mock(AgentPlanner.class);
    private final AgentExecutor executor = mock(AgentExecutor.class);
    private final AgentCritic critic = mock(AgentCritic.class);
    private final WorkflowClock clock = mock(WorkflowClock.class);
    private final AgentWorkflowProperties properties = new AgentWorkflowProperties();
    private final WorkflowDecisionRunner stages = new WorkflowDecisionRunner();
    private final PlannerExecutorCriticWorkflow workflow = new PlannerExecutorCriticWorkflow(
            planner, executor, critic, clock, properties, stages);
    private final Request request = new Request(7L, 51L, List.of(11L), "解释事务消息");
    private final Plan plan = new Plan(Route.VIDEO_RAG,
            List.of(new Step("s1", "VIDEO_TIMELINE_RETRIEVAL", "解释事务消息")), 0);

    PlannerExecutorCriticWorkflowTest() {
        when(clock.now()).thenReturn(Instant.parse("2026-08-15T00:00:00Z"));
    }

    @Test
    void returnsOnlyEvidenceExplicitlyAcceptedByCritic() {
        Evidence accepted = evidence("ev-1");
        Evidence rejected = evidence("ev-2");
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(plan);
        when(executor.execute(any(), any())).thenReturn(step("s1", QueryOrigin.ORIGINAL,
                List.of(accepted, rejected)));
        when(critic.review(any(), any(), any(), any(Integer.class), anyLong()))
                .thenReturn(critique(Verdict.ACCEPT, List.of("ev-1"), null));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.candidates()).containsExactly(accepted, rejected);
        assertThat(result.evidence()).containsExactly(accepted);
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void replansOnceAndRetainsOriginalQueryEvidenceAsFallback() {
        Plan rewrittenPlan = new Plan(Route.VIDEO_RAG,
                List.of(new Step("s2", "VIDEO_TIMELINE_RETRIEVAL", "RocketMQ 事务消息",
                        QueryOrigin.REWRITE_1)), 1);
        Evidence original = evidence("original");
        Evidence rewritten = evidence("rewritten");
        QuerySet querySet = new QuerySet("解释事务消息", List.of("RocketMQ 事务消息"));
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(plan, rewrittenPlan);
        when(executor.execute(any(), any())).thenReturn(
                step("s1", QueryOrigin.ORIGINAL, List.of(original)),
                step("s2", QueryOrigin.REWRITE_1, List.of(rewritten)));
        when(critic.review(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(
                critique(Verdict.REPLAN, List.of(), querySet),
                critique(Verdict.ACCEPT, List.of("original"), null));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.replans()).isEqualTo(1);
        assertThat(result.candidates()).containsExactly(original, rewritten);
        assertThat(result.evidence()).containsExactly(original);
        verify(executor, times(2)).execute(any(), any());
    }

    @Test
    void failsClosedWhenPlannerDecisionIsUnavailable() {
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong()))
                .thenThrow(new WorkflowDecisionUnavailableException("planner unavailable",
                        new IllegalStateException("decision failed")));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.VERIFICATION_UNAVAILABLE);
        assertThat(result.evidence()).isEmpty();
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void rejectsOutOfScopeBeforeAnyRetrievalAndNeverPublishesReferences() {
        Plan out = new Plan(Route.OUT_OF_SCOPE, List.of(), 0, "OUTSIDE_SELECTED_KNOWLEDGE");
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(out);

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.OUT_OF_SCOPE);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        verify(executor, never()).execute(any(), any());
        verify(critic, never()).review(any(), any(), any(), any(Integer.class), anyLong());
    }

    @Test
    void stopsBeforeExceedingTheGlobalToolBudget() {
        properties.setMaxToolCalls(2);
        Plan three = new Plan(Route.VIDEO_RAG, List.of(
                new Step("s1", "VIDEO_TIMELINE_RETRIEVAL", "a"),
                new Step("s2", "VIDEO_TIMELINE_RETRIEVAL", "b"),
                new Step("s3", "VIDEO_TIMELINE_RETRIEVAL", "c")), 0);
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(three);
        when(executor.execute(any(), any())).thenReturn(step("s", QueryOrigin.ORIGINAL, List.of()));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.TOOL_BUDGET_EXCEEDED);
        assertThat(result.evidence()).isEmpty();
        verify(executor, times(2)).execute(any(), any());
    }

    @Test
    void configurationCannotRaiseTheHardSixToolAndOneReplanCaps() {
        properties.setMaxToolCalls(99);
        properties.setMaxReplans(99);
        Plan seven = new Plan(Route.VIDEO_RAG, List.of(
                new Step("s1", "VIDEO_TIMELINE_RETRIEVAL", "a"),
                new Step("s2", "VIDEO_TIMELINE_RETRIEVAL", "b"),
                new Step("s3", "VIDEO_TIMELINE_RETRIEVAL", "c"),
                new Step("s4", "VIDEO_TIMELINE_RETRIEVAL", "d"),
                new Step("s5", "VIDEO_TIMELINE_RETRIEVAL", "e"),
                new Step("s6", "VIDEO_TIMELINE_RETRIEVAL", "f"),
                new Step("s7", "VIDEO_TIMELINE_RETRIEVAL", "g")), 0);
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(seven);
        when(executor.execute(any(), any())).thenReturn(step("s", QueryOrigin.ORIGINAL, List.of()));

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.TOOL_BUDGET_EXCEEDED);
        assertThat(result.toolCalls()).isEqualTo(6);
        verify(executor, times(6)).execute(any(), any());
    }

    @Test
    void emitsBoundedWorkflowEventsInOrder() {
        List<WorkflowEvent> events = new ArrayList<>();
        Request observed = new Request(7L, 51L, request.scope(), request.conversation(), "解释事务消息",
                events::add, WorkflowCancellation.NONE);
        Evidence accepted = evidence("ev-1");
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(plan);
        when(executor.execute(any(), any())).thenReturn(step("s1", QueryOrigin.ORIGINAL, List.of(accepted)));
        when(critic.review(any(), any(), any(), any(Integer.class), anyLong()))
                .thenReturn(critique(Verdict.ACCEPT, List.of("ev-1"), null));

        assertThat(workflow.run(observed).status()).isEqualTo(Status.COMPLETED);

        assertThat(events).extracting(event -> event.phase() + ":" + event.status())
                .containsExactly("PLAN:COMPLETED", "TOOL:STARTED", "TOOL:COMPLETED",
                        "CRITIC:ACCEPT", "WORKFLOW:COMPLETED");
        assertThat(events.get(2).evidenceIds()).containsExactly("ev-1");
    }

    @Test
    void cancellationAfterPlanningPreventsAnyToolCall() {
        AtomicInteger checks = new AtomicInteger();
        WorkflowCancellation cancellation = () -> {
            if (checks.incrementAndGet() >= 3) throw new WorkflowCancelledException("cancelled");
        };
        Request cancellable = new Request(7L, 51L, request.scope(), request.conversation(),
                request.question(), WorkflowObserver.NOOP, cancellation);
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(plan);

        assertThatThrownBy(() -> workflow.run(cancellable))
                .isInstanceOf(WorkflowCancelledException.class);
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void executorCallsShareAndRespectTheConfiguredPhaseBudget() {
        properties.setExecutorTimeoutMillis(5);
        when(planner.plan(any(), any(), any(), any(Integer.class), anyLong())).thenReturn(plan);
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return step("s1", QueryOrigin.ORIGINAL, List.of());
        });

        var result = workflow.run(request);

        assertThat(result.status()).isEqualTo(Status.DEADLINE_EXCEEDED);
        assertThat(result.toolCalls()).isZero();
        verify(critic, never()).review(any(), any(), any(), any(Integer.class), anyLong());
    }

    private StepResult step(String id, QueryOrigin origin, List<Evidence> evidence) {
        return new StepResult(id, "VIDEO_TIMELINE_RETRIEVAL",
                origin == QueryOrigin.ORIGINAL ? "解释事务消息" : "RocketMQ 事务消息",
                origin, evidence, null);
    }

    private Critique critique(Verdict verdict, List<String> accepted, QuerySet querySet) {
        return new Critique(verdict, "TEST", "test", querySet, List.of(), accepted);
    }

    private Evidence evidence(String id) {
        return new Evidence(id, 11L, 1L, 1L, 0, 0, "title", "heading",
                "content-" + id, "parent", 0L, 1_000L, 0.1, 0.9, 0.8);
    }
}
