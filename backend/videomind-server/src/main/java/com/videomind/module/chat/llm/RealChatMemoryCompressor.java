package com.videomind.module.chat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.ai.AiApiSupport;
import com.videomind.module.chat.entity.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "real")
public class RealChatMemoryCompressor implements ChatMemoryCompressor {

    private static final int MESSAGE_CONTENT_LIMIT = 1000;

    private final AiProperties aiProperties;
    private final RestClient aiRestClient;

    @Override
    public String compress(String existingSummary, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return existingSummary;
        }
        AiProperties.ApiProvider chat = aiProperties.getChat();
        AiApiSupport.requireConfigured("Chat memory compressor", chat);

        JsonNode response = aiRestClient.post()
                .uri(chat.getEndpoint())
                .headers(headers -> AiApiSupport.setBearerAuth(headers, chat.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildRequest(existingSummary, messages, chat))
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
            throw new BizException(500, "Chat memory compressor response does not contain text content.");
        }
        return content.strip();
    }

    private Map<String, Object> buildRequest(String existingSummary, List<ChatMessage> messages, AiProperties.ApiProvider chat) {
        List<Map<String, String>> requestMessages = new ArrayList<>();
        requestMessages.add(Map.of(
                "role", "system",
                "content", """
                        You are a conversation memory compressor for VideoMind.
                        Merge the existing memory summary and the new chat history into one concise long-term memory.
                        Keep user goals, confirmed facts, important constraints, unresolved questions, and video-related context.
                        Remove greetings, duplicated wording, and irrelevant details.
                        Do not invent facts. Write in Chinese. Keep the result within 800 Chinese characters.
                        """
        ));
        requestMessages.add(Map.of(
                "role", "user",
                "content", buildUserPrompt(existingSummary, messages)
        ));

        Map<String, Object> request = new LinkedHashMap<>();
        if (StringUtils.hasText(chat.getModel())) {
            request.put("model", chat.getModel());
        }
        request.put("messages", requestMessages);
        request.put("temperature", 0.1);
        request.put("max_tokens", 1200);
        return request;
    }

    private String buildUserPrompt(String existingSummary, List<ChatMessage> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Existing memory summary:\n");
        prompt.append(StringUtils.hasText(existingSummary) ? existingSummary : "(none)");
        prompt.append("\n\nNew chat history to merge:\n");
        for (ChatMessage message : messages) {
            prompt.append("- messageId=").append(message.getId())
                    .append(", role=").append(message.getRole())
                    .append(": ")
                    .append(shorten(message.getContent(), MESSAGE_CONTENT_LIMIT))
                    .append('\n');
        }
        prompt.append("\nReturn only the updated memory summary.");
        return prompt.toString();
    }

    private String shorten(String text, int maxLength) {
        if (!StringUtils.hasText(text) || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
