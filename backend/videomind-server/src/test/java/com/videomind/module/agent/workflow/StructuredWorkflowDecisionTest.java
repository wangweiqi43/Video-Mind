package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.QuerySet;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Route;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredWorkflowDecisionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final QueryRewriteGuard rewriteGuard = new QueryRewriteGuard();
    private final AgentWorkflowProperties properties = new AgentWorkflowProperties();
    private final StructuredLlmAgentPlanner planner = new StructuredLlmAgentPlanner(
            mock(WorkflowDecisionClient.class), mapper);
    private final StructuredLlmAgentCritic critic = new StructuredLlmAgentCritic(
            mock(WorkflowDecisionClient.class), mapper, rewriteGuard, properties);
    private final Request request = new Request(7L, 51L, List.of(10L, 20L),
            "RocketMQ 5.0 的 TransactionListener.checkLocalTransaction 在 30 秒内如何执行");

    @Test
    void firstPlanAlwaysExecutesTheOriginalQuestionAndRejectsUnknownTools() {
        var plan = planner.parse("""
                {"route":"VIDEO_RAG","reasonCode":"VIDEO_FACT",
                 "steps":[{"id":"s1","tool":"VIDEO_TIMELINE_RETRIEVAL",
                           "input":"模型试图替换问题","queryOrigin":"ORIGINAL"}]}
                """, request, null, 0, 6);

        assertThat(plan.generation()).isZero();
        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.input()).isEqualTo(request.question());
            assertThat(step.queryOrigin()).isEqualTo(AgentWorkflowModels.QueryOrigin.ORIGINAL);
        });
        assertThatThrownBy(() -> planner.parse("""
                {"route":"VIDEO_RAG","reasonCode":"BAD",
                 "steps":[{"id":"s1","tool":"WEB_SEARCH","input":"q","queryOrigin":"ORIGINAL"}]}
                """, request, null, 0, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstPlanDeduplicatesCallsThatBecomeIdenticalAfterOriginalQueryIsRestored() {
        var plan = planner.parse("""
                {"route":"VIDEO_RAG","reasonCode":"VIDEO_FACT",
                 "steps":[
                   {"id":"s1","tool":"VIDEO_TIMELINE_RETRIEVAL",
                    "input":"第一种模型改写","queryOrigin":"ORIGINAL"},
                   {"id":"s2","tool":"VIDEO_TIMELINE_RETRIEVAL",
                    "input":"第二种模型改写","queryOrigin":"ORIGINAL"}
                 ]}
                """, request, null, 0, 6);

        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.id()).isEqualTo("s1");
            assertThat(step.input()).isEqualTo(request.question());
            assertThat(step.queryOrigin()).isEqualTo(AgentWorkflowModels.QueryOrigin.ORIGINAL);
        });
    }

    @Test
    void replanCanOnlyExecuteAValidatedRewriteFromTheCriticQuerySet() {
        String rewrite = "解释 RocketMQ 5.0 的 TransactionListener.checkLocalTransaction 在 30 秒内的执行流程";
        Critique prior = new Critique(Verdict.REPLAN, "RECALL_LOW", "召回不足",
                new QuerySet(request.question(), List.of(rewrite)), List.of(), List.of());

        Plan plan = planner.parse("""
                {"route":"VIDEO_RAG","reasonCode":"REWRITE_RETRIEVAL",
                 "steps":[{"id":"s2","tool":"VIDEO_TIMELINE_RETRIEVAL",
                           "input":"%s","queryOrigin":"REWRITE_1"}]}
                """.formatted(rewrite), request, prior, 1, 5);

        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.input()).isEqualTo(rewrite);
            assertThat(step.queryOrigin()).isEqualTo(AgentWorkflowModels.QueryOrigin.REWRITE_1);
        });
        assertThatThrownBy(() -> planner.parse("""
                {"route":"VIDEO_RAG","reasonCode":"UNAPPROVED",
                 "steps":[{"id":"s2","tool":"VIDEO_TIMELINE_RETRIEVAL",
                           "input":"另一个查询","queryOrigin":"REWRITE_1"}]}
                """, request, prior, 1, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void criticAcceptsOnlyKnownEvidenceIds() {
        Evidence evidence = evidence("ev-1");
        Plan plan = new Plan(Route.VIDEO_RAG, List.of(), 0);

        Critique result = critic.parse("""
                {"verdict":"ACCEPT","reasonCode":"SUPPORTED","reason":"证据直接支撑",
                 "acceptedEvidenceIds":["ev-1"],"protectedTerms":[]}
                """, request, plan, List.of(evidence), 0);

        assertThat(result.acceptedEvidenceIds()).containsExactly("ev-1");
        assertThatThrownBy(() -> critic.parse("""
                {"verdict":"ACCEPT","reasonCode":"SUPPORTED","reason":"证据直接支撑",
                 "acceptedEvidenceIds":["invented"],"protectedTerms":[]}
                """, request, plan, List.of(evidence), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRewriteThatDropsTechnicalTermsAndNumbersIsDiscarded() {
        Plan plan = new Plan(Route.VIDEO_RAG, List.of(), 0);

        Critique result = critic.parse("""
                {"verdict":"REPLAN","reasonCode":"RECALL_LOW","reason":"需要改写",
                 "acceptedEvidenceIds":[],
                 "querySet":{"originalQuery":"被模型覆盖","rewrittenQueries":["解释本地检查流程"]},
                 "protectedTerms":["RocketMQ","TransactionListener.checkLocalTransaction","30"]}
                """, request, plan, List.of(), 0);

        assertThat(result.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(result.querySet().originalQuery()).isEqualTo(request.question());
        assertThat(result.querySet().rewrittenQueries()).isEmpty();
    }

    private Evidence evidence(String id) {
        return new Evidence(id, 10L, 1L, 1L, 0, 0, "title", "heading",
                "RocketMQ evidence", "parent", 0L, 1_000L, 0.1, 0.9, 0.8);
    }
}
