package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.ConversationTurn;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ConversationSummary;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationContextServiceImpl implements ConversationContextService {

    private static final int MAX_UNCOMPRESSED_TURNS = 16;
    private static final int RECENT_REMAIN_TURNS = 8;
    private static final Duration CONTEXT_TTL = Duration.ofHours(2);
    private static final String CONTEXT_KEY_PREFIX = "qa:ctx:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationSummaryService conversationSummaryService;
    private final ConversationTurnAssembler turnAssembler;

    @Override
    public ConversationContext getContext(Long conversationId, Long userId) {
        ConversationContext cached = readFromRedis(conversationId);
        if (cached != null) {
            touch(conversationId);
            return cached;
        }
        ConversationContext context = buildFromMysql(conversationId, userId);
        writeToRedis(context);
        return context;
    }

    @Override
    public void refreshContext(Long conversationId, Long userId) {
        writeToRedis(buildFromMysql(conversationId, userId));
    }

    @Override
    public void evictContext(Long conversationId) {
        try {
            stringRedisTemplate.delete(key(conversationId));
        } catch (Exception ex) {
            log.warn("Failed to evict conversation context cache, conversationId={}", conversationId, ex);
        }
    }

    private ConversationContext readFromRedis(Long conversationId) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key(conversationId));
            if (!StringUtils.hasText(json)) {
                return null;
            }
            ConversationContext context = objectMapper.readValue(json, ConversationContext.class);
            return conversationId.equals(context.getConversationId()) ? context : null;
        } catch (Exception ex) {
            log.warn("Failed to read conversation context cache, falling back to MySQL, conversationId={}", conversationId, ex);
            return null;
        }
    }

    private ConversationContext buildFromMysql(Long conversationId, Long userId) {
        ConversationSummary summary = conversationSummaryService.getActiveSummary(conversationId);
        LambdaQueryWrapper<ChatMessage> query = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, conversationId)
                .eq(ChatMessage::getUserId, userId)
                .orderByAsc(ChatMessage::getId);
        if (summary != null) {
            query.gt(ChatMessage::getId, summary.getCoveredEndMessageId());
        }
        List<ConversationTurn> turns = turnAssembler.assemble(chatMessageMapper.selectList(query));
        int contextTurns = summary == null
                ? MAX_UNCOMPRESSED_TURNS
                : Math.max(RECENT_REMAIN_TURNS, Math.min(MAX_UNCOMPRESSED_TURNS, turns.size()));
        int start = Math.max(0, turns.size() - contextTurns);
        return ConversationContext.builder()
                .conversationId(conversationId)
                .summary(toSnapshot(summary))
                .recentTurns(List.copyOf(turns.subList(start, turns.size())))
                .updatedAt(LocalDateTime.now().toString())
                .build();
    }

    private ConversationContext.SummarySnapshot toSnapshot(ConversationSummary summary) {
        if (summary == null) {
            return null;
        }
        return ConversationContext.SummarySnapshot.builder()
                .summaryId(summary.getId())
                .summaryText(summary.getSummaryText())
                .coveredStartMessageId(summary.getCoveredStartMessageId())
                .coveredEndMessageId(summary.getCoveredEndMessageId())
                .coveredTurnCount(summary.getCoveredTurnCount())
                .build();
    }

    private void writeToRedis(ConversationContext context) {
        try {
            stringRedisTemplate.opsForValue().set(
                    key(context.getConversationId()),
                    objectMapper.writeValueAsString(context),
                    CONTEXT_TTL
            );
        } catch (Exception ex) {
            log.warn("Failed to write conversation context cache, conversationId={}", context.getConversationId(), ex);
        }
    }

    private void touch(Long conversationId) {
        try {
            stringRedisTemplate.expire(key(conversationId), CONTEXT_TTL);
        } catch (Exception ex) {
            log.warn("Failed to refresh conversation context TTL, conversationId={}", conversationId, ex);
        }
    }

    private String key(Long conversationId) {
        return CONTEXT_KEY_PREFIX + conversationId;
    }
}
