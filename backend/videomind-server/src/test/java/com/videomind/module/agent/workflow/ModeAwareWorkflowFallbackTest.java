package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Mode;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModeAwareWorkflowFallbackTest {
    private final RuleBasedAgentPlanner rules = mock(RuleBasedAgentPlanner.class);
    private final StructuredLlmAgentPlanner structuredPlanner = mock(StructuredLlmAgentPlanner.class);
    private final EvidenceAgentCritic evidence = mock(EvidenceAgentCritic.class);
    private final StructuredLlmAgentCritic structuredCritic = mock(StructuredLlmAgentCritic.class);
    private final AgentWorkflowProperties properties = new AgentWorkflowProperties();
    private final WorkflowDecisionRunner decisions = new WorkflowDecisionRunner();
    private final Plan fallback = new Plan("RULE", List.of(
            new Step("s1", "HYBRID_RETRIEVAL", "q")), 0);

    @Test
    void standardModeNeverCallsLlmPlanner() {
        Request request = request(Mode.STANDARD);
        when(rules.plan(request, null, null, 0)).thenReturn(fallback);
        ModeAwareAgentPlanner planner = new ModeAwareAgentPlanner(rules, structuredPlanner, properties, decisions);

        assertThat(planner.plan(request, null, null, 0)).isSameAs(fallback);

        verify(structuredPlanner, never()).plan(any(), any(), any(), any(Integer.class));
    }

    @Test
    void invalidDeepPlanFallsBackToBoundedRules() {
        Request request = request(Mode.DEEP);
        when(structuredPlanner.plan(request, null, null, 0))
                .thenThrow(new IllegalArgumentException("invalid json"));
        when(rules.plan(request, null, null, 0)).thenReturn(fallback);
        ModeAwareAgentPlanner planner = new ModeAwareAgentPlanner(rules, structuredPlanner, properties, decisions);

        assertThat(planner.plan(request, null, null, 0)).isSameAs(fallback);
    }

    @Test
    void timedOutDeepPlanFallsBackToBoundedRules() {
        properties.setDecisionTimeoutMillis(20);
        Request request = request(Mode.DEEP);
        doAnswer(call -> {
            Thread.sleep(200);
            return fallback;
        }).when(structuredPlanner).plan(request, null, null, 0);
        when(rules.plan(request, null, null, 0)).thenReturn(fallback);
        ModeAwareAgentPlanner planner = new ModeAwareAgentPlanner(rules, structuredPlanner, properties, decisions);

        assertThat(planner.plan(request, null, null, 0)).isSameAs(fallback);
    }

    @Test
    void invalidDeepCritiqueFallsBackToEvidenceRules() {
        Request request = request(Mode.DEEP);
        Critique fallbackCritique = new Critique(Verdict.REPLAN, "rule");
        when(structuredCritic.review(request, fallback, List.of(), 0))
                .thenThrow(new IllegalArgumentException("invalid json"));
        when(evidence.review(request, fallback, List.of(), 0)).thenReturn(fallbackCritique);
        ModeAwareAgentCritic critic = new ModeAwareAgentCritic(evidence, structuredCritic, properties, decisions);

        assertThat(critic.review(request, fallback, List.of(), 0)).isSameAs(fallbackCritique);
    }

    private Request request(Mode mode) {
        return new Request(7L, List.of(11L), "q", mode);
    }
}
