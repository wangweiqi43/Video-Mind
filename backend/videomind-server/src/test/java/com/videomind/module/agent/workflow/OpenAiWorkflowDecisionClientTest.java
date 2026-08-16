package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videomind.config.AiProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OpenAiWorkflowDecisionClientTest {

    @Test
    void sendsOrderedMessagesWithDeterministicTemperature() {
        AiProperties properties = new AiProperties();
        properties.getChat().setMode("real");
        ChatModel model = mock(ChatModel.class);
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"route\":\"RETRIEVE\"}")).build();
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);

        String result = new OpenAiWorkflowDecisionClient(properties, provider)
                .decide("planner-system", "planner-user");

        assertThat(result).isEqualTo("{\"route\":\"RETRIEVE\"}");
        assertThat(captured.get().temperature()).isZero();
        assertThat(captured.get().messages()).hasSize(2);
        assertThat(((SystemMessage) captured.get().messages().get(0)).text()).isEqualTo("planner-system");
        assertThat(((UserMessage) captured.get().messages().get(1)).singleText()).isEqualTo("planner-user");
    }
}
