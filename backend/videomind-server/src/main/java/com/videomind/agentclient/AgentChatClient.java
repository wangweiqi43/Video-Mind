package com.videomind.agentclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.chat.dto.RagReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AgentChatClient {

    private final AgentApiClient apiClient;
    private final AgentClientProperties properties;
    private final ObjectMapper objectMapper;

    public String createConversation(String knowledgeBaseId, Long userId, String idempotencyKey, String traceId) {
        JsonNode response = apiClient.post(
                "/v1/conversations",
                Map.of("title", "VideoMind 高级会话", "knowledgeBaseIds", List.of(knowledgeBaseId)),
                AgentRequestContext.of(properties.getTenantId(), userId, idempotencyKey, traceId)
        );
        String id = response.path("id").asText();
        if (!StringUtils.hasText(id)) {
            throw new AgentClientException("INVALID_RESPONSE", "MindAgent 创建会话响应缺少 id", null, false);
        }
        return id;
    }

    public AgentChatResult chatConversation(
            String conversationId, String question, AgentToolPolicy toolPolicy, boolean deepThinking, Long userId,
            String idempotencyKey, String traceId, Consumer<String> onDelta
    ) {
        StringBuilder answer = new StringBuilder();
        List<RagReference> references = new ArrayList<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", question);
        payload.put("toolPolicy", toolPolicy);
        payload.put("reasoningMode", deepThinking ? "deep" : "standard");
        apiClient.postSse(
                "/v1/conversations/" + conversationId + "/messages:stream",
                payload,
                AgentRequestContext.of(properties.getTenantId(), userId, idempotencyKey, traceId),
                event -> handleEvent(event, answer, references, onDelta)
        );
        return new AgentChatResult(answer.toString(), List.copyOf(references));
    }

    public JsonNode listMessages(String conversationId, Long userId, String traceId) {
        return apiClient.get("/v1/conversations/" + conversationId + "/messages",
                AgentRequestContext.of(properties.getTenantId(), userId, "read-messages", traceId));
    }

    public AgentChatResult chat(
            AgentChatRequest request,
            Long userId,
            String idempotencyKey,
            String traceId,
            Consumer<String> onDelta
    ) {
        StringBuilder answer = new StringBuilder();
        List<RagReference> references = new ArrayList<>();
        apiClient.postSse(
                "/v1/chat",
                request,
                AgentRequestContext.of(properties.getTenantId(), userId, idempotencyKey, traceId),
                event -> handleEvent(event, answer, references, onDelta)
        );
        return new AgentChatResult(answer.toString(), List.copyOf(references));
    }

    private void handleEvent(
            AgentApiClient.AgentSseEvent event,
            StringBuilder answer,
            List<RagReference> references,
            Consumer<String> onDelta
    ) {
        JsonNode node = parse(event.data());
        String type = node == null ? event.event() : node.path("type").asText(event.event());
        if ("delta".equalsIgnoreCase(type) || "message".equalsIgnoreCase(type) || "content".equalsIgnoreCase(type)) {
            String delta = node == null
                    ? event.data()
                    : node.path("delta").asText(node.path("content").asText(node.path("text").asText("")));
            if (delta != null && !delta.isEmpty()) {
                answer.append(delta);
                onDelta.accept(delta);
            }
        }
        if (node != null) {
            JsonNode refs = node.has("references") ? node.get("references") : node.get("sources");
            if (refs != null && refs.isArray()) {
                refs.forEach(ref -> references.add(toReference(ref)));
            } else if ("reference".equalsIgnoreCase(type) || "source".equalsIgnoreCase(type)) {
                references.add(toReference(node.has("reference") ? node.get("reference") : node));
            }
            if ("done".equalsIgnoreCase(type)) {
                String finalAnswer = node.path("answer").asText("");
                if (!finalAnswer.isEmpty()) {
                    answer.setLength(0);
                    answer.append(finalAnswer);
                }
            }
        }
    }

    private JsonNode parse(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception ignored) {
            return null;
        }
    }

    private RagReference toReference(JsonNode ref) {
        return RagReference.builder()
                .videoId(longValue(ref, "videoId"))
                .taskId(longValue(ref, "taskId"))
                .chunkIndex(intValue(ref, "chunkIndex"))
                .chunkText(text(ref, "chunkText", "snippet", "text"))
                .score(doubleValue(ref, "score"))
                .sourceType(text(ref, "sourceType", "type"))
                .title(text(ref, "title"))
                .domain(text(ref, "domain"))
                .publishedAt(text(ref, "publishedAt", "publishedTime"))
                .url(text(ref, "url", "link"))
                .startSeconds(intValue(ref, "startSeconds", "timestampSeconds"))
                .endSeconds(intValue(ref, "endSeconds"))
                .build();
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name)) {
                return node.get(name).asText();
            }
        }
        return null;
    }

    private Long longValue(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.get(name).asLong() : null;
    }

    private Integer intValue(JsonNode node, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name)) {
                return node.get(name).asInt();
            }
        }
        return null;
    }

    private Double doubleValue(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.get(name).asDouble() : null;
    }

    public record AgentChatRequest(
            Long videoId,
            String knowledgeBaseId,
            Long sessionId,
            String question,
            String answerScope,
            String answerPolicy,
            AgentToolPolicy toolPolicy,
            String conversationSummary,
            List<Map<String, String>> recentTurns
    ) {
    }

    public record AgentToolPolicy(boolean knowledgeBase, boolean webSearch, boolean deepResearch, boolean pptGeneration) {
        public AgentToolPolicy(boolean knowledgeBase, boolean webSearch, boolean deepResearch) {
            this(knowledgeBase, webSearch, deepResearch, false);
        }
    }

    public record AgentChatResult(String answer, List<RagReference> references) {
    }
}
