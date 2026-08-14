package com.videomind.module.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentChatClient;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.common.enums.MessageRole;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatSession;
import com.videomind.module.chat.llm.ChatAnswerClient;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.repository.KnowledgeSearchRepository;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

class ChatServiceHistoryTest {

    private ChatSessionMapper sessionMapper;
    private ChatMessageMapper messageMapper;
    private VideoFileService videoFileService;
    private AgentChatClient agentChatClient;
    private ChatServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "ChatServiceHistoryTest"),
                ChatSession.class);
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        videoFileService = mock(VideoFileService.class);
        agentChatClient = mock(AgentChatClient.class);
        service = new ChatServiceImpl(
                sessionMapper,
                messageMapper,
                mock(EmbeddingClient.class),
                mock(KnowledgeSearchRepository.class),
                mock(ChatAnswerClient.class),
                mock(ConversationContextService.class),
                mock(ConversationSummaryService.class),
                mock(ConversationTurnAssembler.class),
                objectMapper,
                videoFileService,
                new AgentClientProperties(),
                agentChatClient
        );
        when(videoFileService.getVideoDetail(7L, 99L)).thenReturn(new VideoFile());
    }

    @Test
    void listsOnlyCurrentUserVideoAndAdvancedModeWithoutCallingMindAgent() {
        ChatSession session = advancedSession();
        session.setLastMessagePreview("本章需要重点掌握全部黑色小标题");
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));

        var result = service.listSessions(7L, "advanced", 99L);

        assertEquals(1, result.size());
        assertEquals("本章需要重点掌握全部黑色小标题", result.get(0).getLastMessagePreview());
        ArgumentCaptor<LambdaQueryWrapper<ChatSession>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sessionMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<ChatSession> wrapper = wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("user_id"));
        assertTrue(wrapper.getSqlSegment().contains("video_id"));
        assertTrue(wrapper.getSqlSegment().contains("application_mode"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(99L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("ADVANCED"));
        verifyNoInteractions(messageMapper, agentChatClient);
    }

    @Test
    void restoresAdvancedMessagesFromMappedMindAgentConversationInOrder() throws Exception {
        ChatSession session = advancedSession();
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(agentChatClient.listMessages(eq("conv-7"), eq(99L), isNull())).thenReturn(objectMapper.readTree("""
                [
                  {"role":"user","content":"问题","createdAt":"2026-07-20T12:00:00+08:00"},
                  {"role":"assistant","content":"### 回答","referencesJson":[{"sourceType":"WEB","url":"https://example.com"}],"createdAt":"2026-07-20T12:01:00+08:00"}
                ]
                """));

        List<ChatMessage> messages = service.listMessages(13L, 7L, 99L);

        assertEquals(List.of(MessageRole.USER, MessageRole.ASSISTANT), messages.stream().map(ChatMessage::getRole).toList());
        assertEquals("### 回答", messages.get(1).getContent());
        assertTrue(messages.get(1).getReferencesJson().contains("https://example.com"));
        assertEquals(LocalDateTime.of(2026, 7, 20, 12, 0), messages.get(0).getCreatedTime());
        verify(agentChatClient).listMessages("conv-7", 99L, null);
        verify(agentChatClient, never()).chatConversation(any(), any(), any(), anyBoolean(), any(), any(), any(), any());
        verifyNoInteractions(messageMapper);
    }

    private ChatSession advancedSession() {
        ChatSession session = new ChatSession();
        session.setId(13L);
        session.setUserId(99L);
        session.setVideoId(7L);
        session.setApplicationMode("ADVANCED");
        session.setMindagentConversationId("conv-7");
        session.setTitle("核心复习要求");
        session.setCreatedTime(LocalDateTime.of(2026, 7, 20, 12, 0));
        session.setUpdatedTime(LocalDateTime.of(2026, 7, 20, 12, 18));
        return session;
    }
}
