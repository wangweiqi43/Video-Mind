package com.videomind.module.agent.workflow;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class BoundedLlmAgentCritic implements AgentCritic {
    static final long MAX_CRITIC_TIMEOUT_MILLIS = 30_000;
    private final EvidenceAgentCritic evidenceCritic;
    private final StructuredLlmAgentCritic structured;
    private final AgentWorkflowProperties properties;
    private final WorkflowDecisionRunner decisions;

    @Override
    public Critique review(Request request, Plan plan, List<StepResult> results, int replans,
                           long timeoutMillis) {
        Critique safety = evidenceCritic.review(request, plan, results, replans, timeoutMillis);
        if (safety.verdict() == Verdict.FAIL) return safety;
        try {
            return decisions.run(() -> structured.review(request, plan, results, replans),
                    Math.min(decisionTimeout(), Math.max(1, timeoutMillis)), request.cancellation());
        } catch (WorkflowCancelledException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            log.warn("PEC critic unavailable, replan={}, failureType={}",
                    replans, failure.getClass().getSimpleName());
            throw new WorkflowDecisionUnavailableException("PEC_CRITIC_UNAVAILABLE", failure);
        }
    }

    long decisionTimeout() {
        return Math.min(MAX_CRITIC_TIMEOUT_MILLIS, Math.max(1, properties.getCriticTimeoutMillis()));
    }
}
