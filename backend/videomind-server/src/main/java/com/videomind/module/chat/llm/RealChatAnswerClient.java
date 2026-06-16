package com.videomind.module.chat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.ai.AiApiSupport;
import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "real")
public class RealChatAnswerClient implements ChatAnswerClient {

    private final AiProperties aiProperties;
    private final RestClient aiRestClient;
    private final ObjectMapper objectMapper;

    @Override
    public String answer(String question, List<RagReference> references, List<ChatMessage> recentMessages, String memorySummary) {
        AiProperties.ApiProvider chat = aiProperties.getChat();
        AiApiSupport.requireConfigured("对话大模型", chat);

        JsonNode response = aiRestClient.post()
                .uri(chat.getEndpoint())
                .headers(headers -> AiApiSupport.setBearerAuth(headers, chat.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildRequest(question, references, recentMessages, memorySummary, chat))
                .retrieve()
                .body(JsonNode.class);

        String content = AiApiSupport.firstText(
                response,
                "choices[0].message.content",
                "data.choices[0].message.content",
                "answer",
                "text",
                "data.text"
        );
        if (!StringUtils.hasText(content)) {
            throw new BizException(500, "对话 API 响应中没有找到回答内容，请调整 RealChatAnswerClient.answer 字段映射。");
        }
        return content;
    }

    @Override
    public void streamAnswer(
            String question,
            List<RagReference> references,
            List<ChatMessage> recentMessages,
            String memorySummary,
            Consumer<String> onDelta
    ) {
        AiProperties.ApiProvider chat = aiProperties.getChat();
        AiApiSupport.requireConfigured("对话大模型", chat);

        try {
            Map<String, Object> requestBody = buildRequest(question, references, recentMessages, memorySummary, chat);
            requestBody.put("stream", true);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chat.getEndpoint()))
                    .header("Authorization", "Bearer " + chat.getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new BizException(500, "对话 API 流式响应失败，HTTP " + response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String delta = parseStreamDelta(data);
                    if (StringUtils.hasText(delta)) {
                        onDelta.accept(delta);
                    }
                }
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "对话 API 流式调用失败：" + ex.getMessage());
        }
    }

    private Map<String, Object> buildRequest(
            String question,
            List<RagReference> references,
            List<ChatMessage> recentMessages,
            String memorySummary,
            AiProperties.ApiProvider chat
    ) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(references, memorySummary)));
        for (ChatMessage message : recentMessages) {
            messages.add(Map.of(
                    "role", message.getRole().name().toLowerCase(),
                    "content", message.getContent()
            ));
        }
        messages.add(Map.of("role", "user", "content", question));

        Map<String, Object> request = new LinkedHashMap<>();
        if (StringUtils.hasText(chat.getModel())) {
            request.put("model", chat.getModel());
        }
        request.put("messages", messages);
        request.put("temperature", 0.3);
        return request;
    }

    private String buildSystemPrompt(List<RagReference> references, String memorySummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 VideoMind 智能助手。请优先基于视频知识片段回答，并在信息不足时明确说明。");
        if (StringUtils.hasText(memorySummary)) {
            prompt.append("\n\n历史摘要记忆：\n").append(memorySummary);
        }
        if (!references.isEmpty()) {
            prompt.append("\n\n检索到的视频知识片段：");
            for (int i = 0; i < references.size(); i++) {
                RagReference ref = references.get(i);
                prompt.append("\n[").append(i + 1).append("] videoId=").append(ref.getVideoId())
                        .append(", taskId=").append(ref.getTaskId())
                        .append(", chunkType=").append(ref.getChunkType())
                        .append(", chunkIndex=").append(ref.getChunkIndex())
                        .append("\n").append(ref.getChunkText());
            }
        } else {
            prompt.append("\n\n当前选中视频没有检索到可用知识片段。回答时请明确说明无法基于该视频知识库确认，")
                    .append("并建议用户先完成解析并加入知识库。");
        }
        return prompt.toString();
    }

    private String parseStreamDelta(String data) throws Exception {
        JsonNode response = objectMapper.readTree(data);
        String delta = AiApiSupport.firstText(
                response,
                "choices[0].delta.content",
                "data.choices[0].delta.content",
                "choices[0].message.content",
                "data.choices[0].message.content",
                "delta",
                "text",
                "data.text"
        );
        return delta == null ? "" : delta;
    }
}
