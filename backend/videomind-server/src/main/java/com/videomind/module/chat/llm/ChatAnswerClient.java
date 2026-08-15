package com.videomind.module.chat.llm;

import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.generation.ChatGenerationCancellationToken;
import java.util.List;
import java.util.function.Consumer;

public interface ChatAnswerClient {

    String answer(
            String question,
            List<RagReference> references,
            List<ChatMessage> recentMessages,
            String memorySummary,
            String answerScope
    );

    default void streamAnswer(
            String question,
            List<RagReference> references,
            List<ChatMessage> recentMessages,
            String memorySummary,
            String answerScope,
            Consumer<String> onDelta
    ) {
        String content = answer(question, references, recentMessages, memorySummary, answerScope);
        for (int i = 0; i < content.length(); i++) {
            onDelta.accept(content.substring(i, i + 1));
        }
    }

    default void streamAnswer(
            String question,
            List<RagReference> references,
            List<ChatMessage> recentMessages,
            String memorySummary,
            String answerScope,
            Consumer<String> onDelta,
            ChatGenerationCancellationToken cancellation
    ) {
        cancellation.check();
        streamAnswer(question, references, recentMessages, memorySummary, answerScope, delta -> {
            cancellation.check();
            onDelta.accept(delta);
        });
        cancellation.check();
    }
}
