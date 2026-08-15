package com.videomind.module.agent.workflow;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Mode;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class ModeAwareAgentCritic implements AgentCritic {
    private final EvidenceAgentCritic evidenceCritic;
    private final StructuredLlmAgentCritic structured;
    private final AgentWorkflowProperties properties;
    private final WorkflowDecisionRunner decisions;

    @Override
    public Critique review(Request request, Plan plan, List<StepResult> results, int replans) {
        Critique safety = evidenceCritic.review(request, plan, results, replans);
        if (request.mode() != Mode.DEEP || safety.verdict() != AgentWorkflowModels.Verdict.ACCEPT) {
            return safety;
        }
        try {
            return decisions.run(() -> structured.review(request, plan, results, replans), decisionTimeout());
        } catch (RuntimeException failure) {
            log.warn("Structured workflow critic failed; using evidence rules, replan={}, reason={}",
                    replans, failure.getClass().getSimpleName());
            return evidenceCritic.review(request, plan, results, replans);
        }
    }

    private long decisionTimeout() {
        return Math.min(10_000, Math.max(1, properties.getDecisionTimeoutMillis()));
    }
}
