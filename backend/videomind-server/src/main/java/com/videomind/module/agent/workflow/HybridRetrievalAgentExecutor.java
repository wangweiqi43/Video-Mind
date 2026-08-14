package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.knowledge.retrieval.HybridRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HybridRetrievalAgentExecutor implements AgentExecutor {
    private final HybridRetrievalService retrieval;

    @Override
    public StepResult execute(Request request, Step step) {
        if (!"HYBRID_RETRIEVAL".equals(step.tool())) {
            throw new IllegalArgumentException("unsupported agent tool: " + step.tool());
        }
        return new StepResult(step.id(), retrieval.retrieve(request.userId(), request.knowledgeBaseIds(), step.input()));
    }
}
