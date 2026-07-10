package com.videomind.agentclient;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AgentTaskClient {

    private final AgentApiClient apiClient;
    private final AgentClientProperties properties;

    public AgentTaskResult ingest(AgentIngestRequest request, Long userId, String idempotencyKey, String traceId) {
        JsonNode response = apiClient.post(
                "/v1/ingest",
                request,
                AgentRequestContext.of(properties.getTenantId(), userId, idempotencyKey, traceId)
        );
        return taskResult(response);
    }

    public AgentTaskResult createPresentation(
            Long videoId,
            String knowledgeBaseId,
            PresentationOptions options,
            Long userId,
            String idempotencyKey,
            String traceId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("videoId", videoId);
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("options", options);
        JsonNode response = apiClient.post(
                "/v1/presentations",
                payload,
                AgentRequestContext.of(properties.getTenantId(), userId, idempotencyKey, traceId)
        );
        return taskResult(response);
    }

    private AgentTaskResult taskResult(JsonNode response) {
        String taskId = response.path("taskId").asText();
        if (!StringUtils.hasText(taskId)) {
            throw new AgentClientException("INVALID_RESPONSE", 502, "Agent Platform 响应缺少 taskId", false);
        }
        return new AgentTaskResult(
                taskId,
                response.path("status").asText("PENDING"),
                response.path("knowledgeBaseId").asText(null),
                response.path("artifactId").asText(null),
                response.path("downloadUrl").asText(null)
        );
    }

    public record AgentIngestRequest(
            Long videoId,
            Long sourceTaskId,
            Integer transcriptVersion,
            String transcriptUrl,
            String videoUrl,
            String language,
            Map<String, Object> metadata
    ) {
    }

    public record PresentationOptions(
            String template,
            String language,
            Integer slideCount,
            String audience,
            String tone
    ) {
    }

    public record AgentTaskResult(
            String taskId,
            String status,
            String knowledgeBaseId,
            String artifactId,
            String downloadUrl
    ) {
    }
}
