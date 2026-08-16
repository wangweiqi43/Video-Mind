package com.videomind.module.agent.workflow;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BoundedLlmAgentPlanner implements AgentPlanner {
    static final long MAX_PLANNER_TIMEOUT_MILLIS = 30_000;
    private final StructuredLlmAgentPlanner structured;
    private final AgentWorkflowProperties properties;
    private final WorkflowDecisionRunner decisions;

    @Override
    public Plan plan(Request request, Plan previous, Critique critique, int generation, long timeoutMillis) {
        try {
            int configuredLimit = Math.min(request.maxToolCalls(),
                    Math.max(1, properties.getMaxToolCalls()));
            int remaining = Math.max(0, configuredLimit - generationToolCalls(previous));
            return decisions.run(() -> structured.plan(request, previous, critique, generation, remaining),
                    Math.min(decisionTimeout(), Math.max(1, timeoutMillis)), request.cancellation());
        } catch (WorkflowCancelledException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            log.warn("PEC planner unavailable, generation={}, failureType={}, failureCode={}",
                    generation, failure.getClass().getSimpleName(), failure.getMessage());
            throw new WorkflowDecisionUnavailableException("PEC_PLANNER_UNAVAILABLE", failure);
        }
    }

    private int generationToolCalls(Plan previous) {
        return previous == null ? 0 : previous.steps().size();
    }

    long decisionTimeout() {
        return Math.min(MAX_PLANNER_TIMEOUT_MILLIS, Math.max(1, properties.getPlannerTimeoutMillis()));
    }
}
