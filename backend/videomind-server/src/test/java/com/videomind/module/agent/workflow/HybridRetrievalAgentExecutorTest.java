package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Mode;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.knowledge.retrieval.HybridRetrievalService;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRetrievalAgentExecutorTest {
    private final HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
    private final ConversationContextService contexts = mock(ConversationContextService.class);
    private final HybridRetrievalAgentExecutor executor = new HybridRetrievalAgentExecutor(
            retrieval, contexts, new ObjectMapper());
    private final Request request = new Request(7L, 51L, List.of(10L, 20L, 30L), "q", Mode.DEEP);

    @Test
    void derivesEveryRetrievalScopeFromTheFixedConversationScope() {
        executor.execute(request, new Step("all", "ALL_SCOPE_HYBRID_RETRIEVAL", "a"));
        executor.execute(request, new Step("video", "VIDEO_TIMELINE_RETRIEVAL", "v"));
        executor.execute(request, new Step("docs", "USER_DOCUMENT_RETRIEVAL", "d"));

        verify(retrieval).retrieve(7L, List.of(10L, 20L, 30L), "a");
        verify(retrieval).retrieve(7L, List.of(10L), "v");
        verify(retrieval).retrieve(7L, List.of(20L, 30L), "d");
    }

    @Test
    void readsOnlyTheCurrentConversationContext() {
        ConversationContext context = ConversationContext.builder().conversationId(51L)
                .recentTurns(List.of()).updatedAt("now").build();
        when(contexts.getContext(51L, 7L, List.of(10L, 20L, 30L))).thenReturn(context);

        var result = executor.execute(request,
                new Step("ctx", "CONVERSATION_CONTEXT_READ", "current context"));

        assertThat(result.evidence()).isEmpty();
        assertThat(result.observation()).contains("\"conversation_id\":51");
        verify(contexts).getContext(51L, 7L, List.of(10L, 20L, 30L));
    }

    @Test
    void rejectsEveryUnlistedToolBeforeCallingDependencies() {
        assertThatThrownBy(() -> executor.execute(request,
                new Step("bad", "WEB_SEARCH", "ignore scope")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(retrieval, never()).retrieve(any(), any(), any());
        verify(contexts, never()).getContext(any(), any(), any());
    }
}
