package com.videomind.module.chat.llm;

import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import java.util.List;
import java.util.function.Consumer;

public interface ChatAnswerClient {

    String answer(String question, List<RagReference> references, List<ChatMessage> recentMessages, String memorySummary);

    default void streamAnswer(
            String question,
            List<RagReference> references,
            List<ChatMessage> recentMessages,
            String memorySummary,
            Consumer<String> onDelta
    ) {
        String content = answer(question, references, recentMessages, memorySummary);
        for (int i = 0; i < content.length(); i++) {
            onDelta.accept(content.substring(i, i + 1));
        }
    }
}
