package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EvidenceAgentCritic implements AgentCritic {
    @Override
    public Critique review(Request request, Plan plan, List<StepResult> results, int replans) {
        long evidence = results.stream().mapToLong(value -> value.evidence().size()).sum();
        if (evidence > 0) {
            return new Critique(Verdict.ACCEPT, "已取得可引用证据");
        }
        return replans == 0 ? new Critique(Verdict.REPLAN, "首次检索无证据，需要扩展查询")
                : new Critique(Verdict.FAIL, "扩展检索后仍无证据");
    }
}
