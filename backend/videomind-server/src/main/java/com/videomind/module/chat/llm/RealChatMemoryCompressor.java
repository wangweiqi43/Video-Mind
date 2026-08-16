package com.videomind.module.chat.llm;

import com.videomind.common.exception.BizException;
import com.videomind.config.LangChain4jModelConfig;
import com.videomind.module.chat.entity.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "real")
public class RealChatMemoryCompressor implements ChatMemoryCompressor {

    private static final int MESSAGE_CONTENT_LIMIT = 1000;
    private static final String SYSTEM_PROMPT = """
            You are a conversation memory compressor for VideoMind.
            Merge the existing memory summary and the new chat history into one concise long-term memory.
            Keep user goals, confirmed facts, important constraints, unresolved questions, and video-related context.
            Remove greetings, duplicated wording, and irrelevant details.
            Do not invent facts. Write in Chinese. Keep the result within 800 Chinese characters.
            """;

    private final ChatModel chatModel;

    public RealChatMemoryCompressor(
            @Qualifier(LangChain4jModelConfig.CHAT_MODEL) ChatModel chatModel
    ) {
        this.chatModel = chatModel;
    }

    @Override
    public String compress(String existingSummary, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return existingSummary;
        }
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(buildUserPrompt(existingSummary, messages)))
                .temperature(0.1)
                .maxOutputTokens(1200)
                .build();
        String content;
        try {
            content = chatModel.chat(request).aiMessage().text();
        } catch (RuntimeException ex) {
            throw new BizException(500, "Chat memory compressor model call failed: " + safeMessage(ex));
        }
        if (!StringUtils.hasText(content)) {
            throw new BizException(500, "Chat memory compressor response does not contain text content.");
        }
        return content.strip();
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

    private static String safeMessage(Throwable error) {
        return StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName();
    }
}
