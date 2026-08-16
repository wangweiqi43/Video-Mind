package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import java.util.List;

public interface AgentCritic {
    Critique review(Request request, Plan plan, List<StepResult> results, int replans, long timeoutMillis);

    default Critique review(Request request, Plan plan, List<StepResult> results, int replans) {
        return review(request, plan, results, replans, Long.MAX_VALUE);
    }
}
