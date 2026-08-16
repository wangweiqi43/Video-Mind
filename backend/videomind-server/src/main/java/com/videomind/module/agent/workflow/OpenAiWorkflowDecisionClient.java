package com.videomind.module.agent.workflow;

import com.videomind.config.AiProperties;
import com.videomind.config.LangChain4jModelConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpenAiWorkflowDecisionClient implements WorkflowDecisionClient {

    private final AiProperties aiProperties;
    private final ObjectProvider<ChatModel> workflowModel;

    public OpenAiWorkflowDecisionClient(
            AiProperties aiProperties,
            @Qualifier(LangChain4jModelConfig.WORKFLOW_MODEL) ObjectProvider<ChatModel> workflowModel
    ) {
        this.aiProperties = aiProperties;
        this.workflowModel = workflowModel;
    }

    @Override
    public String decide(String systemPrompt, String userPrompt) {
        if (!"real".equalsIgnoreCase(aiProperties.getChat().getMode())) {
            throw new IllegalStateException("WORKFLOW_LLM_DISABLED");
        }
        ChatModel model = workflowModel.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("WORKFLOW_LANGCHAIN4J_MODEL_UNAVAILABLE");
        }
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
                .temperature(0.0)
                .build();
        String content;
        try {
            content = model.chat(request).aiMessage().text();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("WORKFLOW_LLM_CALL_FAILED", ex);
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("WORKFLOW_LLM_EMPTY_RESPONSE");
        }
        return content;
    }
}
