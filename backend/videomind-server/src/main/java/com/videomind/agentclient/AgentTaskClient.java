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

    public AgentTaskSnapshot task(String taskId, Long userId, String traceId) {
        JsonNode response = apiClient.get(
                "/v1/tasks/" + taskId,
                AgentRequestContext.of(properties.getTenantId(), userId, null, traceId)
        );
        return taskSnapshot(response);
    }

    public AgentTaskResult retry(String taskId, Long userId, String idempotencyKey, String traceId) {
        JsonNode response = apiClient.post(
                "/v1/tasks/" + taskId + ":retry",
                Map.of(),
                AgentRequestContext.of(properties.getTenantId(), userId, idempotencyKey, traceId)
        );
        return taskResult(response);
    }

    public AgentTaskResult createResearch(Long videoId, String knowledgeBaseId, String question,
                                          boolean webSearch, int targetLength, int transcriptVersion,String sourceTitle,Long userId,
                                          String idempotencyKey, String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("knowledgeBaseIds", java.util.List.of(knowledgeBaseId));
        payload.put("webSearch", webSearch);
        payload.put("targetLength", targetLength);
        payload.put("deepThinking", true);
        payload.put("sourceVideoId", videoId);
        payload.put("sourceVersion", transcriptVersion);
        payload.put("sourceTitle", sourceTitle);
        payload.put("publishReportKnowledgeBase", true);
        JsonNode response = apiClient.post("/v1/research", payload,
                AgentRequestContext.of(properties.getTenantId(), userId, idempotencyKey, traceId));
        return taskResult(response);
    }

    public JsonNode researchReport(String reportId, Long userId) {
        return apiClient.get("/v1/research/" + reportId,
                AgentRequestContext.of(properties.getTenantId(), userId, null, null));
    }

    public void deleteVideoKnowledge(Long videoId,Long userId,String traceId){apiClient.delete("/v1/integrations/videomind/videos/"+videoId,AgentRequestContext.of(properties.getTenantId(),userId,null,traceId));}

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

    private AgentTaskSnapshot taskSnapshot(JsonNode response) {
        String taskId = response.path("taskId").asText();
        if (!StringUtils.hasText(taskId)) {
            throw new AgentClientException("INVALID_RESPONSE", 502, "Agent Platform 任务响应缺少 taskId", false);
        }
        JsonNode result = response.path("result");
        return new AgentTaskSnapshot(
                taskId,
                response.path("status").asText("PENDING"),
                response.path("stage").asText(null),
                response.path("progress").asInt(0),
                response.path("errorCode").asText(null),
                response.path("errorMessage").asText(null),
                result.path("knowledgeBaseId").asText(null),
                result
        );
    }

    public record AgentIngestRequest(
            Long videoId,
            Long sourceTaskId,
            Integer transcriptVersion,
            String transcriptUrl,
            String language,
            Boolean generateSummary,
            Map<String, Object> metadata
    ) {
        public AgentIngestRequest(Long videoId, Long sourceTaskId, Integer transcriptVersion,
                                  String transcriptUrl, String language, Map<String, Object> metadata) {
            this(videoId, sourceTaskId, transcriptVersion, transcriptUrl, language, true, metadata);
        }
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

    public record AgentTaskSnapshot(
            String taskId,
            String status,
            String stage,
            Integer progress,
            String errorCode,
            String errorMessage,
            String knowledgeBaseId,
            JsonNode result
    ) {
    }
}
