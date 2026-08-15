package com.videomind.module.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.chat.service.ConversationContextService;
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
    private final ConversationContextService conversationContexts;
    private final ObjectMapper objectMapper;

    @Override
    public StepResult execute(Request request, Step step) {
        return switch (step.tool()) {
            case "ALL_SCOPE_HYBRID_RETRIEVAL" -> retrieve(request, step, request.knowledgeBaseIds());
            case "VIDEO_TIMELINE_RETRIEVAL" -> retrieve(request, step, videoScope(request));
            case "USER_DOCUMENT_RETRIEVAL" -> retrieve(request, step, documentScope(request));
            case "CONVERSATION_CONTEXT_READ" -> context(request, step);
            default -> throw new IllegalArgumentException("unsupported agent tool: " + step.tool());
        };
    }

    private StepResult retrieve(Request request, Step step, java.util.List<Long> scope) {
        return new StepResult(step.id(), step.tool(),
                scope.isEmpty() ? java.util.List.of()
                        : retrieval.retrieve(request.userId(), scope, step.input()), null);
    }

    private StepResult context(Request request, Step step) {
        if (request.conversationId() == null) {
            throw new IllegalArgumentException("conversation context tool requires conversationId");
        }
        try {
            String observation = objectMapper.writeValueAsString(conversationContexts.getContext(
                    request.conversationId(), request.userId(), request.knowledgeBaseIds()));
            return new StepResult(step.id(), step.tool(), java.util.List.of(), observation);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("CONVERSATION_CONTEXT_SERIALIZE_FAILED", failure);
        }
    }

    private java.util.List<Long> videoScope(Request request) {
        if (request.knowledgeBaseIds().isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.of(request.knowledgeBaseIds().get(0));
    }

    private java.util.List<Long> documentScope(Request request) {
        if (request.knowledgeBaseIds().size() < 2) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(request.knowledgeBaseIds().subList(1, request.knowledgeBaseIds().size()));
    }
}
