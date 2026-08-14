package com.videomind.module.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.MessageRole;
import com.videomind.module.agent.workflow.AgentWorkflowModels;
import com.videomind.module.agent.workflow.PlannerExecutorCriticWorkflow;
import com.videomind.module.chat.dto.ChatMessageRequest;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatSession;
import com.videomind.module.chat.llm.ChatAnswerClient;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.service.HotConversationSnapshotService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import com.videomind.module.knowledge.retrieval.Evidence;
import com.videomind.module.knowledge.service.KnowledgeBaseService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatServiceLocalWorkflowTest {
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final ChatAnswerClient answers = mock(ChatAnswerClient.class);
    private final ConversationContextService contexts = mock(ConversationContextService.class);
    private final ConversationSummaryService summaries = mock(ConversationSummaryService.class);
    private final ConversationTurnAssembler turns = mock(ConversationTurnAssembler.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
    private final PlannerExecutorCriticWorkflow workflow = mock(PlannerExecutorCriticWorkflow.class);
    private final HotConversationSnapshotService hot = mock(HotConversationSnapshotService.class);
    private final ChatServiceImpl service = new ChatServiceImpl(sessions, messages, answers, contexts, summaries,
            turns, new ObjectMapper(), videos, knowledgeBases, workflow, hot);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "chat-test"),
                ChatSession.class);
        when(videos.getVideoDetail(7L, 99L)).thenReturn(new VideoFile());
    }

    @Test
    void createsLocalSessionWithValidatedFixedKnowledgeScopeAndHotSnapshot() {
        when(knowledgeBases.requireReadyConversationScope(99L, 7L, List.of(20L)))
                .thenReturn(List.of(10L, 20L));

        var result = service.createSession(7L, List.of(20L), 99L);

        assertThat(result.getKnowledgeBaseIds()).containsExactly(10L, 20L);
        ArgumentCaptor<ChatSession> session = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessions).insert(session.capture());
        assertThat(session.getValue().getApplicationMode()).isEqualTo("LOCAL");
        assertThat(session.getValue().getKnowledgeBaseIdsJson()).isEqualTo("[10,20]");
        verify(hot).write(any(HotConversationSnapshot.class));
    }

    @Test
    void runsPecAgainstSessionScopeAndPersistsLocalEvidence() {
        ChatSession session = session();
        when(sessions.selectOne(any())).thenReturn(session);
        ConversationContext context = ConversationContext.builder().conversationId(13L)
                .recentTurns(List.of()).build();
        when(contexts.getContext(13L, 99L)).thenReturn(context);
        Evidence evidence = new Evidence("ev-1", 20L, 30L, 40L, 2, 2, "用户文档", "章节",
                "幂等状态机", "父段落", 1_000L, 2_000L, 0.03, 0.9, 0.82);
        when(workflow.run(any())).thenReturn(new AgentWorkflowModels.Result(
                AgentWorkflowModels.Status.COMPLETED, null, List.of(), List.of(evidence), 0, 1, "ok"));
        when(turns.toMessages(List.of(), 99L)).thenReturn(List.of());
        when(answers.answer(eq("如何保证幂等"), any(), eq(List.of()), eq(""), any()))
                .thenReturn("使用状态机和 CAS Lease");
        when(messages.selectCount(any())).thenReturn(1L);

        ChatMessageRequest request = new ChatMessageRequest();
        request.setSessionId(13L);
        request.setVideoId(7L);
        request.setQuestion("如何保证幂等");
        var response = service.sendMessage(request, 99L);

        assertThat(response.getAnswer()).isEqualTo("使用状态机和 CAS Lease");
        assertThat(response.getReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
            assertThat(reference.getStartSeconds()).isEqualTo(1);
        });
        ArgumentCaptor<AgentWorkflowModels.Request> workflowRequest =
                ArgumentCaptor.forClass(AgentWorkflowModels.Request.class);
        verify(workflow).run(workflowRequest.capture());
        assertThat(workflowRequest.getValue().knowledgeBaseIds()).containsExactly(10L, 20L);
        verify(hot).write(any(HotConversationSnapshot.class));
    }

    @Test
    void listsSessionsWithoutLegacyModeFilter() {
        when(sessions.selectList(any())).thenReturn(List.of(session()));
        when(messages.selectOne(any())).thenReturn(null);

        assertThat(service.listSessions(7L, 99L)).hasSize(1);

        ArgumentCaptor<LambdaQueryWrapper<ChatSession>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sessions).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("user_id", "video_id").doesNotContain("application_mode");
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setId(13L);
        session.setUserId(99L);
        session.setVideoId(7L);
        session.setTitle("本地会话");
        session.setApplicationMode("LOCAL");
        session.setKnowledgeBaseIdsJson("[10,20]");
        session.setCreatedTime(LocalDateTime.now());
        session.setUpdatedTime(LocalDateTime.now());
        return session;
    }
}
