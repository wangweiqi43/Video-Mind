package com.videomind.module.agent.workflow;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Mode;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class ModeAwareAgentPlanner implements AgentPlanner {
    private final RuleBasedAgentPlanner rules;
    private final StructuredLlmAgentPlanner structured;
    private final AgentWorkflowProperties properties;
    private final WorkflowDecisionRunner decisions;

    @Override
    public Plan plan(Request request, Plan previous, Critique critique, int generation) {
        if (request.mode() != Mode.DEEP) {
            return rules.plan(request, previous, critique, generation);
        }
        try {
            return decisions.run(() -> structured.plan(request, previous, critique, generation), decisionTimeout());
        } catch (RuntimeException failure) {
            log.warn("Structured workflow planner failed; using bounded rules, generation={}, reason={}",
                    generation, failure.getClass().getSimpleName());
            return rules.plan(request, previous, critique, generation);
        }
    }

    private long decisionTimeout() {
        return Math.min(10_000, Math.max(1, properties.getDecisionTimeoutMillis()));
    }
}
