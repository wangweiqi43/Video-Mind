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
        return switch (step.tool()) {
            case "VIDEO_TIMELINE_RETRIEVAL" -> retrieve(request, step, videoScope(request));
            case "USER_DOCUMENT_RETRIEVAL" -> retrieve(request, step, documentScope(request));
            default -> throw new IllegalArgumentException("unsupported agent tool: " + step.tool());
        };
    }

    private StepResult retrieve(Request request, Step step, java.util.List<Long> scope) {
        return new StepResult(step.id(), step.tool(), step.input(), step.queryOrigin(),
                scope.isEmpty() ? java.util.List.of()
                        : retrieval.retrieve(request.userId(), scope, step.input()), null);
    }

    private java.util.List<Long> videoScope(Request request) {
        if (request.scope().videoKnowledgeBaseId() == null) {
            return java.util.List.of();
        }
        return java.util.List.of(request.scope().videoKnowledgeBaseId());
    }

    private java.util.List<Long> documentScope(Request request) {
        return request.scope().documentKnowledgeBaseIds();
    }
}
