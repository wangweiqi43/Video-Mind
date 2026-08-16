package com.videomind.module.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.MessageRole;
import com.videomind.module.chat.entity.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RealChatMemoryCompressorTest {

    @Test
    void preservesMemoryPromptAndOutputLimit() {
        ChatModel model = mock(ChatModel.class);
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return ChatResponse.builder().aiMessage(AiMessage.from("  压缩后的记忆  ")).build();
        });
        ChatMessage message = new ChatMessage();
        message.setId(7L);
        message.setRole(MessageRole.USER);
        message.setContent("第一章需要复习什么？");

        String result = new RealChatMemoryCompressor(model).compress("已有记忆", List.of(message));

        assertThat(result).isEqualTo("压缩后的记忆");
        assertThat(captured.get().temperature()).isEqualTo(0.1);
        assertThat(captured.get().maxOutputTokens()).isEqualTo(1200);
        assertThat(((SystemMessage) captured.get().messages().get(0)).text()).contains("800 Chinese characters");
        assertThat(((UserMessage) captured.get().messages().get(1)).singleText())
                .contains("已有记忆", "messageId=7", "第一章需要复习什么？");
    }
}
