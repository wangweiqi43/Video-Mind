package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.MessageRole;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.workflow.AgentWorkflowModels;
import com.videomind.module.agent.workflow.PlannerExecutorCriticWorkflow;
import com.videomind.module.chat.dto.ChatMessageRequest;
import com.videomind.module.chat.dto.ChatMessageResponse;
import com.videomind.module.chat.dto.ChatSessionCreateResponse;
import com.videomind.module.chat.dto.ChatSessionResponse;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatSession;
import com.videomind.module.chat.llm.ChatAnswerClient;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.service.ChatService;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.service.HotConversationSnapshotService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import com.videomind.module.knowledge.retrieval.Evidence;
import com.videomind.module.knowledge.service.KnowledgeBaseService;
import com.videomind.module.video.service.VideoFileService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private static final String LOCAL_MODE = "LOCAL";
    private static final String NEW_SESSION_TITLE = "新会话";
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatAnswerClient chatAnswerClient;
    private final ConversationContextService conversationContextService;
    private final ConversationSummaryService conversationSummaryService;
    private final ConversationTurnAssembler turnAssembler;
    private final ObjectMapper objectMapper;
    private final VideoFileService videoFileService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final PlannerExecutorCriticWorkflow workflow;
    private final HotConversationSnapshotService hotSnapshots;

    @Override
    public ChatSessionCreateResponse createSession(Long videoId, List<Long> selectedKnowledgeBaseIds, Long userId) {
        videoFileService.getVideoDetail(videoId, userId);
        List<Long> scope = knowledgeBaseService.requireReadyConversationScope(userId, videoId,
                selectedKnowledgeBaseIds);
        LocalDateTime now = LocalDateTime.now();
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setVideoId(videoId);
        session.setTitle(NEW_SESSION_TITLE);
        session.setApplicationMode(LOCAL_MODE);
        session.setKnowledgeBaseIdsJson(json(scope));
        session.setCreatedTime(now);
        session.setUpdatedTime(now);
        chatSessionMapper.insert(session);
        writeHotSnapshot(session, scope, null, 0);
        return ChatSessionCreateResponse.builder().sessionId(session.getId()).videoId(videoId)
                .title(session.getTitle()).knowledgeBaseIds(scope).build();
    }

    @Override
    public List<ChatSessionResponse> listSessions(Long videoId, Long userId) {
        videoFileService.getVideoDetail(videoId, userId);
        return chatSessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId).eq(ChatSession::getVideoId, videoId)
                        .orderByDesc(ChatSession::getUpdatedTime)).stream()
                .map(session -> toSessionResponse(session, userId)).toList();
    }

    @Override
    public ChatMessageResponse sendMessage(ChatMessageRequest request, Long userId) {
        rejectUnsupportedTools(request);
        ChatSession session = getSession(request.getSessionId(), request.getVideoId(), userId);
        ConversationContext context = conversationContextService.getContext(session.getId(), userId);
        insertMessage(session.getId(), userId, MessageRole.USER, request.getQuestion(), null);
        ChatOutcome outcome = answer(request, userId, session, context);
        ChatMessage assistant = insertMessage(session.getId(), userId, MessageRole.ASSISTANT,
                outcome.answer(), json(outcome.references()));
        finishTurn(session, userId, request.getQuestion(), outcome.answer());
        return response(assistant, outcome.references());
    }

    @Override
    public SseEmitter streamMessage(ChatMessageRequest request, Long userId) {
        rejectUnsupportedTools(request);
        SseEmitter emitter = new SseEmitter(0L);
        String traceId = MDC.get("traceId");
        CompletableFuture.runAsync(() -> {
            if (StringUtils.hasText(traceId)) MDC.put("traceId", traceId);
            try {
                doStream(request, userId, emitter);
            } finally {
                MDC.remove("traceId");
            }
        });
        return emitter;
    }

    @Override
    public List<ChatMessage> listMessages(Long sessionId, Long videoId, Long userId) {
        getSession(sessionId, videoId, userId);
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId).eq(ChatMessage::getUserId, userId)
                .orderByAsc(ChatMessage::getCreatedTime));
    }

    private void doStream(ChatMessageRequest request, Long userId, SseEmitter emitter) {
        try {
            ChatSession session = getSession(request.getSessionId(), request.getVideoId(), userId);
            ConversationContext context = conversationContextService.getContext(session.getId(), userId);
            insertMessage(session.getId(), userId, MessageRole.USER, request.getQuestion(), null);
            List<RagReference> references = retrieve(request, userId, session);
            List<ChatMessage> recent = turnAssembler.toMessages(context.getRecentTurns(), userId);
            StringBuilder answer = new StringBuilder();
            chatAnswerClient.streamAnswer(request.getQuestion(), references, recent, summary(context),
                    request.getAnswerScope(), delta -> {
                        answer.append(delta);
                        sendEvent(emitter, "delta", Map.of("delta", delta));
                    });
            ChatMessage assistant = insertMessage(session.getId(), userId, MessageRole.ASSISTANT,
                    answer.toString(), json(references));
            finishTurn(session, userId, request.getQuestion(), answer.toString());
            sendEvent(emitter, "done", response(assistant, references));
            emitter.complete();
        } catch (Exception exception) {
            sendEvent(emitter, "error", exception.getMessage());
            emitter.complete();
        }
    }

    private ChatOutcome answer(ChatMessageRequest request, Long userId, ChatSession session,
                               ConversationContext context) {
        List<RagReference> references = retrieve(request, userId, session);
        String answer = chatAnswerClient.answer(request.getQuestion(), references,
                turnAssembler.toMessages(context.getRecentTurns(), userId), summary(context), request.getAnswerScope());
        return new ChatOutcome(answer, references);
    }

    private List<RagReference> retrieve(ChatMessageRequest request, Long userId, ChatSession session) {
        List<Long> scope = sessionScope(session, userId);
        boolean deep = Boolean.TRUE.equals(request.getDeepThinkingEnabled());
        AgentWorkflowModels.Result result = workflow.run(new AgentWorkflowModels.Request(userId, scope,
                request.getQuestion(), deep ? 4 : 2, Duration.ofSeconds(deep ? 45 : 20)));
        return result.evidence().stream().map(evidence -> reference(evidence, session.getVideoId())).toList();
    }

    private RagReference reference(Evidence value, Long videoId) {
        return RagReference.builder().videoId(videoId).chunkIndex(value.chunkIndex()).chunkText(value.content())
                .score(value.finalScore()).sourceType("KNOWLEDGE_BASE").title(value.title())
                .startSeconds(value.startMs() == null ? null : Math.toIntExact(value.startMs() / 1_000))
                .endSeconds(value.endMs() == null ? null : Math.toIntExact(value.endMs() / 1_000)).build();
    }

    private ChatSession getSession(Long sessionId, Long videoId, Long userId) {
        videoFileService.getVideoDetail(videoId, userId);
        ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId).eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getVideoId, videoId));
        if (session == null) throw new BizException(404, "会话不存在、无权访问或不属于当前视频");
        return session;
    }

    private List<Long> sessionScope(ChatSession session, Long userId) {
        if (StringUtils.hasText(session.getKnowledgeBaseIdsJson())) {
            try {
                List<Long> values = objectMapper.readValue(session.getKnowledgeBaseIdsJson(), new TypeReference<>() { });
                if (!values.isEmpty()) return List.copyOf(values);
            } catch (JsonProcessingException exception) {
                throw new BizException(500, "会话知识库范围损坏");
            }
        }
        List<Long> scope = knowledgeBaseService.requireReadyConversationScope(userId, session.getVideoId(), List.of());
        session.setKnowledgeBaseIdsJson(json(scope));
        session.setApplicationMode(LOCAL_MODE);
        chatSessionMapper.updateById(session);
        return scope;
    }

    private ChatMessage insertMessage(Long sessionId, Long userId, MessageRole role, String content, String refs) {
        ChatMessage value = new ChatMessage();
        value.setSessionId(sessionId);
        value.setUserId(userId);
        value.setRole(role);
        value.setContent(content);
        value.setReferencesJson(refs);
        value.setCreatedTime(LocalDateTime.now());
        chatMessageMapper.insert(value);
        return value;
    }

    private void finishTurn(ChatSession session, Long userId, String question, String answer) {
        if (!StringUtils.hasText(session.getTitle()) || NEW_SESSION_TITLE.equals(session.getTitle())) {
            session.setTitle(shorten(question.strip(), 40));
        }
        session.setLastMessagePreview(shorten(answer == null ? null : answer.strip().replaceAll("\\s+", " "), 512));
        session.setUpdatedTime(LocalDateTime.now());
        session.setApplicationMode(LOCAL_MODE);
        chatSessionMapper.updateById(session);
        conversationSummaryService.compressIfNeeded(session.getId(), userId);
        conversationContextService.refreshContext(session.getId(), userId);
        ConversationContext refreshed = conversationContextService.getContext(session.getId(), userId);
        long completed = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId()).eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getRole, MessageRole.ASSISTANT));
        writeHotSnapshot(session, sessionScope(session, userId), refreshed, completed);
    }

    private void writeHotSnapshot(ChatSession session, List<Long> scope, ConversationContext context, long completed) {
        try {
            long boundary = context == null || context.getSummary() == null ? 0
                    : context.getSummary().getCoveredTurnCount();
            hotSnapshots.write(new HotConversationSnapshot(session.getId(), summary(context), boundary,
                    completed, scope, Instant.now()));
        } catch (RuntimeException exception) {
            log.warn("Hot conversation snapshot unavailable, sessionId={}", session.getId(), exception);
        }
    }

    private ChatSessionResponse toSessionResponse(ChatSession session, Long userId) {
        ChatMessage last = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId()).eq(ChatMessage::getUserId, userId)
                .orderByDesc(ChatMessage::getCreatedTime).last("LIMIT 1"));
        return ChatSessionResponse.builder().id(session.getId()).videoId(session.getVideoId())
                .title(session.getTitle()).lastMessagePreview(last == null ? session.getLastMessagePreview()
                        : shorten(last.getContent(), 120)).knowledgeBaseIds(sessionScope(session, userId))
                .createdTime(session.getCreatedTime()).updatedTime(session.getUpdatedTime()).build();
    }

    private void rejectUnsupportedTools(ChatMessageRequest request) {
        if (Boolean.TRUE.equals(request.getWebSearchEnabled())) {
            throw new BizException(501, "本地单模式尚未配置联网搜索工具");
        }
    }

    private String summary(ConversationContext context) {
        return context == null || context.getSummary() == null ? "" : context.getSummary().getSummaryText();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(500, "JSON 序列化失败：" + exception.getMessage());
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception exception) {
            throw new BizException(500, "发送流式响应失败：" + exception.getMessage());
        }
    }

    private ChatMessageResponse response(ChatMessage message, List<RagReference> references) {
        return ChatMessageResponse.builder().messageId(message.getId()).answer(message.getContent())
                .references(references).referencesJson(message.getReferencesJson())
                .createdTime(message.getCreatedTime()).build();
    }

    private String shorten(String value, int max) {
        return !StringUtils.hasText(value) || value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private record ChatOutcome(String answer, List<RagReference> references) { }
}
