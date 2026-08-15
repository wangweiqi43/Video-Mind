package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedAgentPlanner implements AgentPlanner {
    @Override
    public Plan plan(Request request, Plan previous, Critique critique, int generation) {
        String query = generation == 0 ? request.question()
                : request.question() + "；补充检索视频时间轴、上传文档与上下文中的相关证据";
        return new Plan(generation == 0 ? "HYBRID_RAG" : "HYBRID_RAG_EXPANDED",
                List.of(new Step("retrieve-" + generation, "ALL_SCOPE_HYBRID_RETRIEVAL", query)), generation);
    }
}
