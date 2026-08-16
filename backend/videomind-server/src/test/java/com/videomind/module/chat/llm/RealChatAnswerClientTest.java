package com.videomind.module.chat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.videomind.common.enums.MessageRole;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.generation.ChatGenerationCancellationRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RealChatAnswerClientTest {

    @Test
    void buildsMessagesInSystemHistoryQuestionOrder() {
        RealChatAnswerClient client = new RealChatAnswerClient(mock(ChatModel.class), mock(StreamingChatModel.class));
        ChatMessage history = new ChatMessage();
        history.setRole(MessageRole.ASSISTANT);
        history.setContent("上一轮回答");

        ChatRequest request = client.buildRequest("继续解释", List.of(), List.of(history), "历史摘要", "KNOWLEDGE_ONLY");

        assertThat(request.temperature()).isEqualTo(0.3);
        assertThat(request.messages()).hasSize(3);
        assertThat(((SystemMessage) request.messages().get(0)).text()).contains("历史摘要", "当前没有检索到可验证的知识证据");
        assertThat(((AiMessage) request.messages().get(1)).text()).isEqualTo("上一轮回答");
        assertThat(((UserMessage) request.messages().get(2)).singleText()).isEqualTo("继续解释");
    }

    @Test
    void streamsDeltasInOrder() {
        StreamingHandle handle = new TestStreamingHandle();
        StreamingChatModel streaming = streamingModel((request, responseHandler) -> {
            responseHandler.onPartialResponse(new PartialResponse("第一段"), new PartialResponseContext(handle));
            responseHandler.onPartialResponse(new PartialResponse("第二段"), new PartialResponseContext(handle));
            responseHandler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("第一段第二段")).build());
        });
        RealChatAnswerClient client = new RealChatAnswerClient(mock(ChatModel.class), streaming);
        List<String> deltas = new ArrayList<>();

        client.streamAnswer("问题", List.of(), List.of(), "", "KNOWLEDGE_ONLY", deltas::add);

        assertThat(deltas).containsExactly("第一段", "第二段");
    }

    @Test
    void cancellationCancelsStreamingHandleAndStopsFurtherDeltas() {
        TestStreamingHandle handle = new TestStreamingHandle();
        ChatGenerationCancellationRegistry registry = new ChatGenerationCancellationRegistry();
        var token = registry.activate(91L);
        StreamingChatModel streaming = streamingModel((request, responseHandler) -> {
            responseHandler.onPartialResponse(new PartialResponse("第一段"), new PartialResponseContext(handle));
            responseHandler.onPartialResponse(new PartialResponse("不应发送"), new PartialResponseContext(handle));
            responseHandler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("完成")).build());
        });
        RealChatAnswerClient client = new RealChatAnswerClient(mock(ChatModel.class), streaming);
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> client.streamAnswer("问题", List.of(), List.of(), "", "KNOWLEDGE_ONLY", delta -> {
            deltas.add(delta);
            registry.requestCancellation(91L);
        }, token)).hasMessage("CHAT_GENERATION_CANCELLED");

        assertThat(handle.isCancelled()).isTrue();
        assertThat(deltas).containsExactly("第一段");
    }

    @Test
    void disconnectedConsumerCancelsStreamingHandle() {
        TestStreamingHandle handle = new TestStreamingHandle();
        StreamingChatModel streaming = streamingModel((request, responseHandler) ->
                responseHandler.onPartialResponse(new PartialResponse("第一段"), new PartialResponseContext(handle)));
        RealChatAnswerClient client = new RealChatAnswerClient(mock(ChatModel.class), streaming);

        assertThatThrownBy(() -> client.streamAnswer("问题", List.of(), List.of(), "", "KNOWLEDGE_ONLY",
                delta -> { throw new IllegalStateException("SSE disconnected"); }))
                .hasMessageContaining("SSE disconnected");

        assertThat(handle.isCancelled()).isTrue();
    }

    private StreamingChatModel streamingModel(StreamInvocation invocation) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                invocation.run(request, handler);
            }
        };
    }

    @FunctionalInterface
    private interface StreamInvocation {
        void run(ChatRequest request, StreamingChatResponseHandler handler);
    }

    private static final class TestStreamingHandle implements StreamingHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
