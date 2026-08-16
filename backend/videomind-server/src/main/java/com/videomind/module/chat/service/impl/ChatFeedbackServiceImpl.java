package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.MessageRole;
import com.videomind.common.exception.BizException;
import com.videomind.module.chat.dto.ChatFeedbackRequest;
import com.videomind.module.chat.dto.ChatFeedbackResponse;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatMessageFeedback;
import com.videomind.module.chat.mapper.ChatMessageFeedbackMapper;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.service.ChatFeedbackService;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ChatFeedbackServiceImpl implements ChatFeedbackService {
    public static final Set<String> ALLOWED_REASONS = Set.of(
            "SEMANTIC_DRIFT", "KNOWLEDGE_GROUNDING_ERROR", "MISSING_KEY_POINTS",
            "IRRELEVANT_REFERENCE", "ANSWER_INCOMPLETE", "OTHER");

    private final ChatMessageMapper messages;
    private final ChatMessageFeedbackMapper feedback;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ChatFeedbackResponse save(Long messageId, Long userId, ChatFeedbackRequest request) {
        ChatMessage message = requireFeedbackTarget(messageId, userId);
        List<String> reasons = normalizeReasons(request.getReasonCodes());
        if ("DOWN".equals(request.getRating()) && reasons.isEmpty()) {
            throw new BizException(400, "点踩时至少选择一个原因");
        }
        if ("UP".equals(request.getRating())) reasons = List.of();
        ChatMessageFeedback value = feedback.selectOne(Wrappers.<ChatMessageFeedback>lambdaQuery()
                .eq(ChatMessageFeedback::getUserId, userId)
                .eq(ChatMessageFeedback::getMessageId, messageId));
        LocalDateTime now = LocalDateTime.now();
        if (value == null) {
            value = new ChatMessageFeedback();
            value.setMessageId(messageId);
            value.setGenerationId(message.getGenerationId());
            value.setSessionId(message.getSessionId());
            value.setUserId(userId);
            value.setCreatedTime(now);
        }
        value.setRating(request.getRating());
        value.setReasonCodesJson(json(reasons));
        value.setDetail(normalizeDetail(request.getDetail()));
        value.setUpdatedTime(now);
        if (value.getId() == null) feedback.insert(value); else feedback.updateById(value);
        return response(value, reasons);
    }

    @Override
    @Transactional
    public void delete(Long messageId, Long userId) {
        requireFeedbackTarget(messageId, userId);
        feedback.delete(Wrappers.<ChatMessageFeedback>lambdaQuery()
                .eq(ChatMessageFeedback::getUserId, userId)
                .eq(ChatMessageFeedback::getMessageId, messageId));
    }

    @Override
    public Map<Long, ChatFeedbackResponse> findBySession(Long sessionId, Long userId) {
        List<ChatMessageFeedback> values = feedback.selectList(Wrappers.<ChatMessageFeedback>lambdaQuery()
                .eq(ChatMessageFeedback::getSessionId, sessionId)
                .eq(ChatMessageFeedback::getUserId, userId));
        Map<Long, ChatFeedbackResponse> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(value.getMessageId(), response(value)));
        return Map.copyOf(result);
    }

    public ChatFeedbackResponse response(ChatMessageFeedback value) {
        return response(value, parseReasons(value.getReasonCodesJson()));
    }

    private ChatMessage requireFeedbackTarget(Long messageId, Long userId) {
        ChatMessage message = messages.selectOne(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getId, messageId).eq(ChatMessage::getUserId, userId));
        if (message == null || message.getRole() != MessageRole.ASSISTANT) {
            throw new BizException(404, "助手消息不存在或无权访问");
        }
        if (message.getGenerationId() == null) {
            throw new BizException(409, "历史消息未关联 PEC 执行，暂不支持反馈");
        }
        return message;
    }

    private List<String> normalizeReasons(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (!ALLOWED_REASONS.contains(value)) throw new BizException(400, "包含不支持的反馈原因");
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private String normalizeDetail(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("CHAT_FEEDBACK_JSON_FAILED", failure);
        }
    }

    private List<String> parseReasons(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception failure) {
            return List.of();
        }
    }

    private ChatFeedbackResponse response(ChatMessageFeedback value, List<String> reasons) {
        return ChatFeedbackResponse.builder().messageId(value.getMessageId()).rating(value.getRating())
                .reasonCodes(reasons).detail(value.getDetail()).updatedTime(value.getUpdatedTime()).build();
    }
}
