package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentChatClient;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.common.enums.MessageRole;
import com.videomind.common.exception.BizException;
import com.videomind.module.chat.dto.ChatMessageRequest;
import com.videomind.module.chat.dto.ChatMessageResponse;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.ConversationTurn;
import com.videomind.module.chat.dto.ChatSessionCreateResponse;
import com.videomind.module.chat.dto.ChatSessionResponse;
import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatSession;
import com.videomind.module.chat.llm.ChatAnswerClient;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.service.ChatService;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import com.videomind.module.chat.support.AnswerScopePolicy;
import com.videomind.module.knowledge.dto.KnowledgeSearchResult;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.repository.KnowledgeSearchRepository;
import com.videomind.module.video.service.VideoFileService;
import com.videomind.module.video.entity.VideoFile;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.MDC;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final int RAG_TOP_K = 5;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeSearchRepository knowledgeSearchRepository;
    private final ChatAnswerClient chatAnswerClient;
    private final ConversationContextService conversationContextService;
    private final ConversationSummaryService conversationSummaryService;
    private final ConversationTurnAssembler turnAssembler;
    private final ObjectMapper objectMapper;
    private final VideoFileService videoFileService;
    private final AgentClientProperties agentProperties;
    private final AgentChatClient agentChatClient;

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

        ConversationContext context = conversationContextService.getContext(request.getSessionId(), userId);
        ChatOutcome outcome = answer(request, userId, context);
        List<RagReference> references = outcome.references();
        String answer = outcome.answer();
        String referencesJson = toJson(references);

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(request.getSessionId());
        assistantMessage.setUserId(userId);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setContent(answer);
        assistantMessage.setReferencesJson(referencesJson);
        assistantMessage.setCreatedTime(now);
        chatMessageMapper.insert(assistantMessage);
        conversationSummaryService.compressIfNeeded(session.getId(), userId);
        conversationContextService.refreshContext(session.getId(), userId);
        updateSessionUpdatedTime(session);

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
        String traceId = MDC.get("traceId");
        CompletableFuture.runAsync(() -> {
            if (StringUtils.hasText(traceId)) {
                MDC.put("traceId", traceId);
            }
            try {
                doStreamMessage(request, userId, emitter);
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
                .sourceType("VIDEO")
                .title("视频内容")
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

            ConversationContext context = conversationContextService.getContext(request.getSessionId(), userId);
            StringBuilder answer = new StringBuilder();
            List<RagReference> references;
            if (isAgentChatEnabled(request)) {
                try {
                    AgentChatClient.AgentChatResult agentResult = invokeAgentChat(request, userId, context, delta -> {
                        answer.append(delta);
                        sendEvent(emitter, "delta", delta);
                    });
                    references = agentResult.references();
                    if (answer.isEmpty() && StringUtils.hasText(agentResult.answer())) {
                        answer.append(agentResult.answer());
                    }
                } catch (Exception ex) {
                    if (!agentProperties.isFallbackOnError() || !answer.isEmpty()) {
                        throw ex;
                    }
                    references = streamLegacyAnswer(request, userId, context, answer, emitter);
                }
            } else {
                references = streamLegacyAnswer(request, userId, context, answer, emitter);
            }
            String referencesJson = toJson(references);

            ChatMessage assistantMessage = new ChatMessage();
            assistantMessage.setSessionId(request.getSessionId());
            assistantMessage.setUserId(userId);
            assistantMessage.setRole(MessageRole.ASSISTANT);
            assistantMessage.setContent(answer.toString());
            assistantMessage.setReferencesJson(referencesJson);
            assistantMessage.setCreatedTime(LocalDateTime.now());
            chatMessageMapper.insert(assistantMessage);
            conversationSummaryService.compressIfNeeded(session.getId(), userId);
            conversationContextService.refreshContext(session.getId(), userId);
            updateSessionUpdatedTime(session);

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

    private ChatOutcome answer(ChatMessageRequest request, Long userId, ConversationContext context) {
        if (isAgentChatEnabled(request)) {
            try {
                AgentChatClient.AgentChatResult result = invokeAgentChat(request, userId, context, ignored -> { });
                return new ChatOutcome(result.answer(), result.references());
            } catch (Exception ex) {
                if (!agentProperties.isFallbackOnError()) {
                    throw ex;
                }
            }
        }
        List<ChatMessage> recentMessages = turnAssembler.toMessages(context.getRecentTurns(), userId);
        List<RagReference> references = searchReferences(request.getQuestion(), userId, request.getVideoId());
        return new ChatOutcome(
                chatAnswerClient.answer(
                        request.getQuestion(), references, recentMessages, summaryText(context), request.getAnswerScope()),
                references
        );
    }

    private List<RagReference> streamLegacyAnswer(
            ChatMessageRequest request,
            Long userId,
            ConversationContext context,
            StringBuilder answer,
            SseEmitter emitter
    ) {
        List<ChatMessage> recentMessages = turnAssembler.toMessages(context.getRecentTurns(), userId);
        List<RagReference> references = searchReferences(request.getQuestion(), userId, request.getVideoId());
        chatAnswerClient.streamAnswer(
                request.getQuestion(), references, recentMessages, summaryText(context), request.getAnswerScope(), delta -> {
            answer.append(delta);
            sendEvent(emitter, "delta", delta);
        });
        return references;
    }

    private AgentChatClient.AgentChatResult invokeAgentChat(
            ChatMessageRequest request,
            Long userId,
            ConversationContext context,
            java.util.function.Consumer<String> onDelta
    ) {
        VideoFile video = videoFileService.getVideoDetail(request.getVideoId(), userId);
        List<Map<String, String>> recentTurns = new ArrayList<>();
        for (ConversationTurn turn : context.getRecentTurns()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("question", turn.getQuestion());
            item.put("answer", turn.getAnswer());
            recentTurns.add(item);
        }
        AgentChatClient.AgentChatRequest agentRequest = new AgentChatClient.AgentChatRequest(
                video.getId(),
                video.getAgentKnowledgeBaseId(),
                request.getSessionId(),
                request.getQuestion(),
                AnswerScopePolicy.normalize(request.getAnswerScope()),
                AnswerScopePolicy.instruction(request.getAnswerScope()),
                summaryText(context),
                recentTurns
        );
        return agentChatClient.chat(
                agentRequest,
                userId,
                "chat:session:" + request.getSessionId() + ":request:" + UUID.randomUUID(),
                null,
                onDelta
        );
    }

    private boolean isAgentChatEnabled(ChatMessageRequest request) {
        return "ADVANCED".equalsIgnoreCase(request.getApplicationMode())
                && agentProperties.isEnabled()
                && agentProperties.isChatEnabled();
    }

    private String toJson(List<RagReference> references) {
        try {
            return objectMapper.writeValueAsString(references);
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "序列化引用片段失败：" + ex.getMessage());
        }
    }

    private String summaryText(ConversationContext context) {
        return context.getSummary() == null ? null : context.getSummary().getSummaryText();
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

    private record ChatOutcome(String answer, List<RagReference> references) {
    }
}
