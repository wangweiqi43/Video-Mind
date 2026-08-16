package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.MessageRole;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.audit.WorkflowAuditService;
import com.videomind.module.agent.workflow.AgentWorkflowModels;
import com.videomind.module.agent.workflow.PlannerExecutorCriticWorkflow;
import com.videomind.module.agent.workflow.WorkflowObserver;
import com.videomind.module.chat.dto.ChatMessageRequest;
import com.videomind.module.chat.dto.ChatMessageResponse;
import com.videomind.module.chat.dto.ChatGenerationStatusResponse;
import com.videomind.module.chat.dto.ChatSessionCreateResponse;
import com.videomind.module.chat.dto.ChatSessionResponse;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.dto.WorkflowSseEvent;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatSession;
import com.videomind.module.chat.generation.ChatGenerationCancelledException;
import com.videomind.module.chat.generation.ChatGenerationCancellationRegistry;
import com.videomind.module.chat.generation.ChatGenerationCancellationToken;
import com.videomind.module.chat.llm.ChatAnswerClient;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.service.ChatService;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import com.videomind.module.knowledge.retrieval.Evidence;
import com.videomind.module.knowledge.service.KnowledgeBaseService;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
    private final WorkflowAuditService workflowAudits;
    private final ChatGenerationCancellationRegistry generationCancellations;

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
        session.setKnowledgeBaseIdsJson(json(scope));
        session.setCreatedTime(now);
        session.setUpdatedTime(now);
        chatSessionMapper.insert(session);
        conversationContextService.refreshContext(session.getId(), userId, scope);
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
        List<Long> scope = sessionScope(session, userId);
        ConversationContext context = conversationContextService.getContext(session.getId(), userId, scope);
        insertMessage(session.getId(), userId, MessageRole.USER, request.getQuestion(), null);
        RetrievalOutcome retrieved = retrieve(request, userId, session, WorkflowObserver.NOOP, ignored -> { });
        try {
            retrieved.cancellation().check();
            String answer = chatAnswerClient.answer(request.getQuestion(), retrieved.references(),
                    turnAssembler.toMessages(context.getRecentTurns(), userId), summary(context),
                    request.getAnswerScope());
            retrieved.cancellation().check();
            if (!retrieved.audit().answerCompleted(answer)) {
                throw new ChatGenerationCancelledException(retrieved.audit().generationId());
            }
            ChatMessage assistant = insertMessage(session.getId(), userId, MessageRole.ASSISTANT,
                    answer, json(retrieved.references()));
            finishTurn(session, userId, request.getQuestion(), answer);
            return response(assistant, retrieved.references());
        } catch (ChatGenerationCancelledException cancelled) {
            retrieved.audit().cancelled(null);
            throw cancelled;
        } catch (RuntimeException failure) {
            retrieved.audit().failed(failure);
            throw failure;
        } finally {
            generationCancellations.release(retrieved.audit().generationId());
        }
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
        WorkflowAuditService.Session audit = null;
        ChatGenerationCancellationToken cancellation = null;
        StringBuilder answer = new StringBuilder();
        try {
            ChatSession session = getSession(request.getSessionId(), request.getVideoId(), userId);
            List<Long> scope = sessionScope(session, userId);
            ConversationContext context = conversationContextService.getContext(session.getId(), userId, scope);
            insertMessage(session.getId(), userId, MessageRole.USER, request.getQuestion(), null);
            RetrievalOutcome retrieved = retrieve(request, userId, session,
                    event -> sendEvent(emitter, "workflow", WorkflowSseEvent.from(event)),
                    generationId -> sendEvent(emitter, "generation",
                            new ChatGenerationStatusResponse(generationId, "RUNNING")));
            audit = retrieved.audit();
            cancellation = retrieved.cancellation();
            List<RagReference> references = retrieved.references();
            List<ChatMessage> recent = turnAssembler.toMessages(context.getRecentTurns(), userId);
            chatAnswerClient.streamAnswer(request.getQuestion(), references, recent, summary(context),
                    request.getAnswerScope(), delta -> {
                        answer.append(delta);
                        sendEvent(emitter, "delta", Map.of("delta", delta));
                    }, cancellation);
            cancellation.check();
            if (!audit.answerCompleted(answer.toString())) {
                throw new ChatGenerationCancelledException(audit.generationId());
            }
            ChatMessage assistant = insertMessage(session.getId(), userId, MessageRole.ASSISTANT,
                    answer.toString(), json(references));
            finishTurn(session, userId, request.getQuestion(), answer.toString());
            sendEvent(emitter, "done", response(assistant, references));
            emitter.complete();
        } catch (ChatGenerationCancelledException cancelled) {
            if (audit != null) {
                audit.cancelled(answer.toString());
            }
            sendEvent(emitter, "cancelled",
                    new ChatGenerationStatusResponse(cancelled.generationId(), "CANCELLED"));
            emitter.complete();
        } catch (Exception exception) {
            if (audit != null) {
                audit.failed(exception);
            }
            sendEvent(emitter, "error", exception.getMessage());
            emitter.complete();
        } finally {
            Long generationId = audit == null ? null : audit.generationId();
            if (generationId != null) {
                generationCancellations.release(generationId);
            }
        }
    }

    private RetrievalOutcome retrieve(ChatMessageRequest request, Long userId, ChatSession session,
                                       WorkflowObserver downstream, Consumer<Long> onGenerationStarted) {
        List<Long> scope = sessionScope(session, userId);
        boolean deep = Boolean.TRUE.equals(request.getDeepThinkingEnabled());
        AgentWorkflowModels.Mode mode = deep ? AgentWorkflowModels.Mode.DEEP : AgentWorkflowModels.Mode.STANDARD;
        AgentWorkflowModels.Request base = new AgentWorkflowModels.Request(userId, session.getId(), scope,
                request.getQuestion(), mode);
        WorkflowAuditService.Session audit = workflowAudits.start(base, downstream);
        ChatGenerationCancellationToken cancellation = generationCancellations.activate(audit.generationId());
        onGenerationStarted.accept(audit.generationId());
        AgentWorkflowModels.Result result;
        try {
            result = workflow.run(new AgentWorkflowModels.Request(userId, session.getId(), scope,
                    request.getQuestion(), mode, audit, cancellation::check));
            audit.workflowFinished(result);
        } catch (ChatGenerationCancelledException cancelled) {
            audit.cancelled(null);
            generationCancellations.release(audit.generationId());
            throw cancelled;
        } catch (RuntimeException failure) {
            audit.failed(failure);
            generationCancellations.release(audit.generationId());
            throw failure;
        }
        Long videoKnowledgeBaseId = scope.isEmpty() ? null : scope.get(0);
        List<RagReference> references = result.evidence().stream()
                .map(evidence -> reference(evidence, session.getVideoId(), videoKnowledgeBaseId)).toList();
        return new RetrievalOutcome(references, audit, cancellation);
    }

    private RagReference reference(Evidence value, Long videoId, Long videoKnowledgeBaseId) {
        String sourceType = java.util.Objects.equals(videoKnowledgeBaseId, value.knowledgeBaseId())
                ? "VIDEO_TIMELINE" : "USER_DOCUMENT";
        return RagReference.builder().evidenceId(value.evidenceId()).knowledgeBaseId(value.knowledgeBaseId())
                .documentId(value.documentId()).documentVersionId(value.documentVersionId())
                .videoId(videoId).chunkIndex(value.chunkIndex()).chunkText(value.content())
                .score(value.finalScore()).sourceType(sourceType).title(value.title())
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
        chatSessionMapper.updateById(session);
        conversationSummaryService.compressIfNeeded(session.getId(), userId);
        conversationContextService.refreshContext(session.getId(), userId, sessionScope(session, userId));
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

    static String shorten(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (max <= 0) {
            return "";
        }
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= max) {
            return value;
        }
        String suffix = "...";
        if (max <= suffix.length()) {
            return suffix.substring(0, max);
        }
        int contentCodePoints = max - suffix.length();
        int endIndex = value.offsetByCodePoints(0, contentCodePoints);
        return value.substring(0, endIndex) + suffix;
    }

    private record RetrievalOutcome(List<RagReference> references, WorkflowAuditService.Session audit,
                                    ChatGenerationCancellationToken cancellation) { }
}
