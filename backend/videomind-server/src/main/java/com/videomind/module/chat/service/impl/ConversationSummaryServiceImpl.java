package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.config.AiProperties;
import com.videomind.module.chat.dto.ConversationTurn;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ConversationSummary;
import com.videomind.module.chat.llm.ChatMemoryCompressor;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ConversationSummaryMapper;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationSummaryServiceImpl implements ConversationSummaryService {

    private static final int COMPRESSION_TRIGGER_TURNS = 16;
    private static final int COMPRESSION_BATCH_TURNS = 8;
    private static final int SUMMARY_VERSION = 1;
    private static final long SUMMARY_LOCK_SECONDS = 60L;
    private static final String ACTIVE = "active";
    private static final String ARCHIVED = "archived";

    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationTurnAssembler turnAssembler;
    private final ChatMemoryCompressor chatMemoryCompressor;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;
    private final AiProperties aiProperties;

    @Override
    public ConversationSummary getActiveSummary(Long conversationId) {
        return conversationSummaryMapper.selectOne(new LambdaQueryWrapper<ConversationSummary>()
                .eq(ConversationSummary::getConversationId, conversationId)
                .eq(ConversationSummary::getStatus, ACTIVE)
                .orderByDesc(ConversationSummary::getCreatedAt)
                .last("LIMIT 1"));
    }

    @Override
    public boolean compressIfNeeded(Long conversationId, Long userId) {
        RLock lock = redissonClient.getLock("qa:summary:lock:" + conversationId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, SUMMARY_LOCK_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return false;
            }

            ConversationSummary activeSummary = getActiveSummary(conversationId);
            List<ChatMessage> uncompressedMessages = listUncompressedMessages(conversationId, userId, activeSummary);
            List<ConversationTurn> uncompressedTurns = turnAssembler.assemble(uncompressedMessages);
            if (uncompressedTurns.size() < COMPRESSION_TRIGGER_TURNS) {
                return false;
            }

            List<ConversationTurn> turnsToCompress = uncompressedTurns.subList(0, COMPRESSION_BATCH_TURNS);
            String oldSummary = activeSummary == null ? null : activeSummary.getSummaryText();
            String summaryText = chatMemoryCompressor.compress(
                    oldSummary,
                    turnAssembler.toMessages(turnsToCompress, userId)
            );
            saveNewSummary(conversationId, activeSummary, turnsToCompress, summaryText);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Conversation summary lock interrupted, conversationId={}", conversationId);
            return false;
        } catch (Exception ex) {
            log.warn("Conversation summary compression failed, conversationId={}", conversationId, ex);
            return false;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private List<ChatMessage> listUncompressedMessages(
            Long conversationId,
            Long userId,
            ConversationSummary activeSummary
    ) {
        LambdaQueryWrapper<ChatMessage> query = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, conversationId)
                .eq(ChatMessage::getUserId, userId)
                .orderByAsc(ChatMessage::getId);
        if (activeSummary != null) {
            query.gt(ChatMessage::getId, activeSummary.getCoveredEndMessageId());
        }
        return chatMessageMapper.selectList(query);
    }

    private void saveNewSummary(
            Long conversationId,
            ConversationSummary oldSummary,
            List<ConversationTurn> turns,
            String summaryText
    ) {
        ConversationTurn firstTurn = turns.get(0);
        ConversationTurn lastTurn = turns.get(turns.size() - 1);
        LocalDateTime now = LocalDateTime.now();
        ConversationSummary newSummary = new ConversationSummary();
        newSummary.setConversationId(conversationId);
        newSummary.setSummaryText(summaryText);
        newSummary.setCoveredStartMessageId(oldSummary == null
                ? firstTurn.getUserMessageId()
                : oldSummary.getCoveredStartMessageId());
        newSummary.setCoveredEndMessageId(lastTurn.getAssistantMessageId());
        newSummary.setCoveredTurnCount((oldSummary == null ? 0 : oldSummary.getCoveredTurnCount()) + turns.size());
        newSummary.setSummaryVersion(SUMMARY_VERSION);
        newSummary.setModelName(aiProperties.getChat().getModel());
        newSummary.setStatus(ACTIVE);
        newSummary.setCreatedAt(now);
        newSummary.setUpdatedAt(now);

        transactionTemplate.executeWithoutResult(status -> {
            conversationSummaryMapper.update(null, Wrappers.<ConversationSummary>lambdaUpdate()
                    .eq(ConversationSummary::getConversationId, conversationId)
                    .eq(ConversationSummary::getStatus, ACTIVE)
                    .set(ConversationSummary::getStatus, ARCHIVED)
                    .set(ConversationSummary::getUpdatedAt, now));
            conversationSummaryMapper.insert(newSummary);
        });
    }
}
