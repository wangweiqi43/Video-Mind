package com.videomind.module.chat.llm;

import com.videomind.module.chat.entity.ChatMessage;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockChatMemoryCompressor implements ChatMemoryCompressor {

    @Override
    public String compress(String existingSummary, List<ChatMessage> messages) {
        StringBuilder summary = new StringBuilder();
        if (StringUtils.hasText(existingSummary)) {
            summary.append(existingSummary).append('\n');
        }
        summary.append("Mock incremental memory: ");
        messages.stream()
                .limit(6)
                .forEach(message -> summary
                        .append('[').append(message.getRole()).append(']')
                        .append(shorten(message.getContent(), 80))
                        .append(' '));
        return shorten(summary.toString(), 1200);
    }

    private String shorten(String text, int maxLength) {
        if (!StringUtils.hasText(text) || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
