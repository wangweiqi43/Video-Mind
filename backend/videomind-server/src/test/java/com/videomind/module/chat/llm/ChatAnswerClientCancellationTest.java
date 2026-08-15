package com.videomind.module.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videomind.module.chat.generation.ChatGenerationCancellationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatAnswerClientCancellationTest {

    @Test
    void stopsDeliveringDeltasAfterCancellation() {
        ChatGenerationCancellationRegistry registry = new ChatGenerationCancellationRegistry();
        var token = registry.activate(61L);
        List<String> delivered = new ArrayList<>();
        ChatAnswerClient client = (question, references, recent, summary, scope) -> "abc";

        assertThatThrownBy(() -> client.streamAnswer("q", List.of(), List.of(), "", "KNOWLEDGE_ONLY",
                delta -> {
                    delivered.add(delta);
                    registry.requestCancellation(61L);
                }, token)).hasMessage("CHAT_GENERATION_CANCELLED");

        assertThat(delivered).containsExactly("a");
    }
}
