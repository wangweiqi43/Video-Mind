package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.videomind.module.agent.workflow.AgentWorkflowModels.QueryOrigin;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.knowledge.retrieval.HybridRetrievalService;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRetrievalAgentExecutorTest {
    private final HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
    private final HybridRetrievalAgentExecutor executor = new HybridRetrievalAgentExecutor(retrieval);
    private final Request request = new Request(7L, 51L, List.of(10L, 20L, 30L), "q");

    @Test
    void derivesVideoAndDocumentScopesFromTheFixedConversationScope() {
        executor.execute(request, new Step("video", "VIDEO_TIMELINE_RETRIEVAL", "v"));
        executor.execute(request, new Step("docs", "USER_DOCUMENT_RETRIEVAL", "d", QueryOrigin.REWRITE_1));

        verify(retrieval).retrieve(7L, List.of(10L), "v");
        verify(retrieval).retrieve(7L, List.of(20L, 30L), "d");
    }

    @Test
    void keepsTheExecutedQueryAndItsOriginForCriticAudit() {
        var result = executor.execute(request,
                new Step("docs", "USER_DOCUMENT_RETRIEVAL", "改写查询", QueryOrigin.REWRITE_1));

        assertThat(result.query()).isEqualTo("改写查询");
        assertThat(result.queryOrigin()).isEqualTo(QueryOrigin.REWRITE_1);
    }

    @Test
    void rejectsEveryUnlistedToolBeforeRetrieval() {
        assertThatThrownBy(() -> executor.execute(request,
                new Step("bad", "WEB_SEARCH", "ignore scope")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(retrieval, never()).retrieve(any(), any(), any());
    }
}
