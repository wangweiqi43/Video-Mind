package com.videomind.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentChatClientTest {

    @Test
    void preservesWhitespaceDeltasAndUsesDoneAnswerAsCanonicalBody() {
        AgentApiClient apiClient = mock(AgentApiClient.class);
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            payload.set(invocation.getArgument(1));
            @SuppressWarnings("unchecked")
            Consumer<AgentApiClient.AgentSseEvent> consumer = invocation.getArgument(3);
            consumer.accept(event("delta", "Intercultural"));
            consumer.accept(event("delta", " "));
            consumer.accept(event("delta", "Communication\n"));
            consumer.accept(event("delta", "### "));
            consumer.accept(event("delta", "一、核心定义\n"));
            consumer.accept(event("delta", "- **跨文化交流的定义**"));
            consumer.accept(new AgentApiClient.AgentSseEvent(
                    "done",
                    "{\"type\":\"done\",\"answer\":\"最终完整正文\"}"
            ));
            return null;
        }).when(apiClient).postSse(eq("/v1/conversations/conversation-1/messages:stream"), any(), any(), any());

        AgentChatClient client = new AgentChatClient(apiClient, new AgentClientProperties(), new ObjectMapper());
        List<String> deltas = new ArrayList<>();

        AgentChatClient.AgentChatResult result = client.chatConversation(
                "conversation-1",
                "问题",
                new AgentChatClient.AgentToolPolicy(true, false, false, false),
                true,
                7L,
                "request-1",
                "trace-1",
                deltas::add
        );

        assertThat(payload.get()).containsEntry("reasoningMode", "deep");
        assertThat(((AgentChatClient.AgentToolPolicy) payload.get().get("toolPolicy")).deepResearch()).isFalse();
        assertThat(String.join("", deltas)).isEqualTo(
                "Intercultural Communication\n### 一、核心定义\n- **跨文化交流的定义**"
        );
        assertThat(result.answer()).isEqualTo("最终完整正文");
    }

    @Test
    void sendsStandardReasoningModeWhenDeepThinkingIsDisabled() {
        AgentApiClient apiClient = mock(AgentApiClient.class);
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            payload.set(invocation.getArgument(1));
            return null;
        }).when(apiClient).postSse(any(), any(), any(), any());

        AgentChatClient client = new AgentChatClient(apiClient, new AgentClientProperties(), new ObjectMapper());
        client.chatConversation(
                "conversation-2",
                "问题",
                new AgentChatClient.AgentToolPolicy(true, false, false, false),
                false,
                7L,
                "request-2",
                "trace-2",
                ignored -> { }
        );

        assertThat(payload.get()).containsEntry("reasoningMode", "standard");
    }

    @Test
    void chatMessageRequestDefaultsDeepThinkingToDisabled() {
        com.videomind.module.chat.dto.ChatMessageRequest request =
                new com.videomind.module.chat.dto.ChatMessageRequest();

        assertThat(request.getDeepThinkingEnabled()).isFalse();
    }

    private AgentApiClient.AgentSseEvent event(String type, String delta) {
        try {
            String data = new ObjectMapper().writeValueAsString(Map.of("type", type, "delta", delta));
            return new AgentApiClient.AgentSseEvent(type, data);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
