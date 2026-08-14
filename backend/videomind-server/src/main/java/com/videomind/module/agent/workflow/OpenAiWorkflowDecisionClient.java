package com.videomind.module.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.ai.AiApiSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class OpenAiWorkflowDecisionClient implements WorkflowDecisionClient {
    private final AiProperties aiProperties;
    private final RestClient aiRestClient;

    @Override
    public String decide(String systemPrompt, String userPrompt) {
        AiProperties.ApiProvider chat = aiProperties.getChat();
        if (!"real".equalsIgnoreCase(chat.getMode())) {
            throw new IllegalStateException("WORKFLOW_LLM_DISABLED");
        }
        AiApiSupport.requireConfigured("工作流决策模型", chat);
        Map<String, Object> body = new LinkedHashMap<>();
        if (StringUtils.hasText(chat.getModel())) {
            body.put("model", chat.getModel());
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("temperature", 0);
        JsonNode response = aiRestClient.post().uri(chat.getEndpoint())
                .headers(headers -> AiApiSupport.setBearerAuth(headers, chat.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        String content = AiApiSupport.firstText(response, "choices[0].message.content",
                "data.choices[0].message.content", "answer", "text", "data.text");
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("WORKFLOW_LLM_EMPTY_RESPONSE");
        }
        return content;
    }
}
