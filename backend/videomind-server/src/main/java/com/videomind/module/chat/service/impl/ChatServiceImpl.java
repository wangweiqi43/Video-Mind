package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.MessageRole;
import com.videomind.common.exception.BizException;
import com.videomind.module.chat.dto.ChatMessageRequest;
import com.videomind.module.chat.dto.ChatMessageResponse;
import com.videomind.module.chat.dto.ChatSessionCreateResponse;
import com.videomind.module.chat.dto.ChatSessionResponse;
import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatSession;
import com.videomind.module.chat.llm.ChatAnswerClient;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.service.ChatService;
import com.videomind.module.knowledge.dto.KnowledgeSearchResult;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.repository.KnowledgeSearchRepository;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final int RAG_TOP_K = 5;
    private static final int WINDOW_MESSAGE_SIZE = 8;
    private static final int SUMMARY_THRESHOLD = 16;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeSearchRepository knowledgeSearchRepository;
    private final ChatAnswerClient chatAnswerClient;
    private final ObjectMapper objectMapper;
    private final VideoFileService videoFileService;

    @Override
    public ChatSessionCreateResponse createSession(Long videoId, Long userId) {
        videoFileService.getVideoDetail(videoId, userId);
        LocalDateTime now = LocalDateTime.now();
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setVideoId(videoId);
        session.setTitle("新会话");
        session.setCreatedTime(now);
        session.setUpdatedTime(now);
        chatSessionMapper.insert(session);

        return ChatSessionCreateResponse.builder()
                .sessionId(session.getId())
                .videoId(session.getVideoId())
                .title(session.getTitle())
                .build();
    }

    @Override
    public List<ChatSessionResponse> listSessions(Long videoId, Long userId) {
        videoFileService.getVideoDetail(videoId, userId);
        return chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getVideoId, videoId)
                .orderByDesc(ChatSession::getUpdatedTime))
                .stream()
                .map(session -> toSessionResponse(session, userId))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageResponse sendMessage(ChatMessageRequest request, Long userId) {
        ChatSession session = getSession(request.getSessionId(), request.getVideoId(), userId);
        updateSessionTitle(session, request.getQuestion());
        LocalDateTime now = LocalDateTime.now();

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(request.getSessionId());
        userMessage.setUserId(userId);
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(request.getQuestion());
        userMessage.setCreatedTime(now);
        chatMessageMapper.insert(userMessage);

        List<ChatMessage> recentMessages = listRecentMessages(request.getSessionId(), userId, WINDOW_MESSAGE_SIZE);
        List<RagReference> references = searchReferences(request.getQuestion(), userId, request.getVideoId());
        String answer = chatAnswerClient.answer(request.getQuestion(), references, recentMessages, session.getMemorySummary());
        String referencesJson = toJson(references);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(request.getSessionId());
        assistantMessage.setUserId(userId);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setContent(answer);
        assistantMessage.setReferencesJson(referencesJson);
        assistantMessage.setCreatedTime(now);
        chatMessageMapper.insert(assistantMessage);
        refreshSessionMemory(session);

        return ChatMessageResponse.builder()
                .messageId(assistantMessage.getId())
                .answer(assistantMessage.getContent())
                .references(references)
                .referencesJson(assistantMessage.getReferencesJson())
                .createdTime(assistantMessage.getCreatedTime())
                .build();
    }

    @Override
    public SseEmitter streamMessage(ChatMessageRequest request, Long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> doStreamMessage(request, userId, emitter));
        return emitter;
    }

    @Override
    public List<ChatMessage> listMessages(Long sessionId, Long videoId, Long userId) {
        getSession(sessionId, videoId, userId);
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getUserId, userId)
                .orderByAsc(ChatMessage::getCreatedTime));
    }

    private ChatSession getSession(Long sessionId, Long videoId, Long userId) {
        videoFileService.getVideoDetail(videoId, userId);
        ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getVideoId, videoId));
        if (session == null) {
            throw new BizException(404, "会话不存在、无权访问或不属于当前视频");
        }
        return session;
    }

    private List<ChatMessage> listRecentMessages(Long sessionId, Long userId, int limit) {
        List<ChatMessage> descMessages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getUserId, userId)
                .orderByDesc(ChatMessage::getCreatedTime)
                .last("LIMIT " + limit));
        Collections.reverse(descMessages);
        return descMessages;
    }

    private List<RagReference> searchReferences(String question, Long userId, Long videoId) {
        float[] queryEmbedding = embeddingClient.embed(question);
        List<KnowledgeSearchResult> results = knowledgeSearchRepository.search(userId, videoId, queryEmbedding, RAG_TOP_K);
        return results.stream()
                .map(this::toReference)
                .toList();
    }

    private RagReference toReference(KnowledgeSearchResult result) {
        return RagReference.builder()
                .videoId(result.getVideoId())
                .taskId(result.getTaskId())
                .chunkType(result.getChunkType())
                .chunkIndex(result.getChunkIndex())
                .chunkText(result.getChunkText())
                .score(result.getScore())
                .build();
    }

    private void doStreamMessage(ChatMessageRequest request, Long userId, SseEmitter emitter) {
        try {
            ChatSession session = getSession(request.getSessionId(), request.getVideoId(), userId);
            updateSessionTitle(session, request.getQuestion());
            LocalDateTime now = LocalDateTime.now();

            ChatMessage userMessage = new ChatMessage();
            userMessage.setSessionId(request.getSessionId());
            userMessage.setUserId(userId);
            userMessage.setRole(MessageRole.USER);
            userMessage.setContent(request.getQuestion());
            userMessage.setCreatedTime(now);
            chatMessageMapper.insert(userMessage);

            List<ChatMessage> recentMessages = listRecentMessages(request.getSessionId(), userId, WINDOW_MESSAGE_SIZE);
            List<RagReference> references = searchReferences(request.getQuestion(), userId, request.getVideoId());
            String referencesJson = toJson(references);
            StringBuilder answer = new StringBuilder();

            chatAnswerClient.streamAnswer(request.getQuestion(), references, recentMessages, session.getMemorySummary(), delta -> {
                answer.append(delta);
                sendEvent(emitter, "delta", delta);
            });

            ChatMessage assistantMessage = new ChatMessage();
            assistantMessage.setSessionId(request.getSessionId());
            assistantMessage.setUserId(userId);
            assistantMessage.setRole(MessageRole.ASSISTANT);
            assistantMessage.setContent(answer.toString());
            assistantMessage.setReferencesJson(referencesJson);
            assistantMessage.setCreatedTime(LocalDateTime.now());
            chatMessageMapper.insert(assistantMessage);
            refreshSessionMemory(session);

            sendEvent(emitter, "done", ChatMessageResponse.builder()
                    .messageId(assistantMessage.getId())
                    .answer(assistantMessage.getContent())
                    .references(references)
                    .referencesJson(assistantMessage.getReferencesJson())
                    .createdTime(assistantMessage.getCreatedTime())
                    .build());
            emitter.complete();
        } catch (Exception ex) {
            sendEvent(emitter, "error", ex.getMessage());
            emitter.complete();
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ex) {
            throw new BizException(500, "发送流式响应失败：" + ex.getMessage());
        }
    }

    private String toJson(List<RagReference> references) {
        try {
            return objectMapper.writeValueAsString(references);
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "序列化引用片段失败：" + ex.getMessage());
        }
    }

    private void refreshSessionMemory(ChatSession session) {
        List<ChatMessage> allMessages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())
                .eq(ChatMessage::getUserId, session.getUserId())
                .orderByAsc(ChatMessage::getCreatedTime));
        if (allMessages.size() < SUMMARY_THRESHOLD) {
            updateSessionUpdatedTime(session);
            return;
        }

        int compactEnd = Math.max(0, allMessages.size() - WINDOW_MESSAGE_SIZE);
        StringBuilder summary = new StringBuilder();
        if (StringUtils.hasText(session.getMemorySummary())) {
            summary.append(session.getMemorySummary()).append('\n');
        }
        summary.append("历史增量摘要：");
        allMessages.subList(0, compactEnd).stream()
                .limit(6)
                .forEach(message -> summary
                        .append('[').append(message.getRole()).append(']')
                        .append(shorten(message.getContent(), 80))
                        .append(' '));
        session.setMemorySummary(shorten(summary.toString(), 1200));
        updateSessionUpdatedTime(session);
    }

    private void updateSessionUpdatedTime(ChatSession session) {
        session.setUpdatedTime(LocalDateTime.now());
        chatSessionMapper.updateById(session);
    }

    private ChatSessionResponse toSessionResponse(ChatSession session, Long userId) {
        ChatMessage lastMessage = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())
                .eq(ChatMessage::getUserId, userId)
                .orderByDesc(ChatMessage::getCreatedTime)
                .last("LIMIT 1"));
        ChatMessage firstUserMessage = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getRole, MessageRole.USER)
                .orderByAsc(ChatMessage::getCreatedTime)
                .last("LIMIT 1"));
        String displayTitle = session.getTitle();
        if ((!StringUtils.hasText(displayTitle) || "新会话".equals(displayTitle)) && firstUserMessage != null) {
            displayTitle = shorten(firstUserMessage.getContent(), 40);
        }
        return ChatSessionResponse.builder()
                .id(session.getId())
                .videoId(session.getVideoId())
                .title(displayTitle)
                .lastMessagePreview(lastMessage == null ? null : shorten(lastMessage.getContent(), 120))
                .createdTime(session.getCreatedTime())
                .updatedTime(session.getUpdatedTime())
                .build();
    }

    private void updateSessionTitle(ChatSession session, String question) {
        if (!"新会话".equals(session.getTitle())) {
            return;
        }
        session.setTitle(shorten(question.strip(), 40));
        session.setUpdatedTime(LocalDateTime.now());
        chatSessionMapper.updateById(session);
    }

    private String shorten(String text, int maxLength) {
        if (!StringUtils.hasText(text) || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
