package com.videomind.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videomind.common.enums.MessageRole;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.ConversationTurn;
import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ConversationSummary;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.service.HotConversationSnapshotService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationContextServiceImpl implements ConversationContextService {

    private static final int MAX_UNCOMPRESSED_TURNS = 16;
    private static final int RECENT_REMAIN_TURNS = 8;

    private final HotConversationSnapshotService hotSnapshots;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationSummaryService conversationSummaryService;
    private final ConversationTurnAssembler turnAssembler;

    @Override
    public ConversationContext getContext(Long conversationId, Long userId, List<Long> knowledgeBaseIds) {
        List<Long> scope = immutableScope(knowledgeBaseIds);
        HotConversationSnapshot cached = readHotSnapshot(conversationId);
        if (isValid(cached, conversationId, scope)) {
            return toContext(cached);
        }
        RebuiltContext rebuilt = buildFromMysql(conversationId, userId);
        writeHotSnapshot(rebuilt, scope);
        return rebuilt.context();
    }

    @Override
    public void refreshContext(Long conversationId, Long userId, List<Long> knowledgeBaseIds) {
        writeHotSnapshot(buildFromMysql(conversationId, userId), immutableScope(knowledgeBaseIds));
    }

    @Override
    public void evictContext(Long conversationId) {
        try {
            hotSnapshots.evict(conversationId);
        } catch (RuntimeException failure) {
            log.warn("Failed to evict hot conversation snapshot, conversationId={}", conversationId, failure);
        }
    }

    private HotConversationSnapshot readHotSnapshot(Long conversationId) {
        try {
            return hotSnapshots.get(conversationId).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Hot conversation snapshot unavailable; rebuilding from MySQL, conversationId={}",
                    conversationId, failure);
            return null;
        }
    }

    private RebuiltContext buildFromMysql(Long conversationId, Long userId) {
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
        ConversationContext context = ConversationContext.builder()
                .conversationId(conversationId)
                .summary(toSnapshot(summary))
                .recentTurns(List.copyOf(turns.subList(start, turns.size())))
                .updatedAt(LocalDateTime.now().toString())
                .build();
        long completedTurns = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, conversationId)
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getRole, MessageRole.ASSISTANT));
        return new RebuiltContext(context, completedTurns);
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

    private void writeHotSnapshot(RebuiltContext rebuilt, List<Long> scope) {
        ConversationContext context = rebuilt.context();
        long boundary = context.getSummary() == null || context.getSummary().getCoveredTurnCount() == null
                ? 0 : context.getSummary().getCoveredTurnCount();
        if (boundary > rebuilt.totalCompletedTurns()) {
            log.warn("Conversation summary boundary exceeds completed turns; skip cache write, conversationId={}",
                    context.getConversationId());
            return;
        }
        String fingerprint = RedisHotConversationSnapshotService.scopeFingerprint(scope);
        HotConversationSnapshot snapshot = new HotConversationSnapshot(
                HotConversationSnapshot.CURRENT_SCHEMA_VERSION, context.getConversationId(), context.getSummary(),
                boundary, rebuilt.totalCompletedTurns(), scope, fingerprint, context.getRecentTurns(), Instant.now());
        try {
            HotConversationSnapshotService.WriteResult result = hotSnapshots.write(snapshot);
            if (result != HotConversationSnapshotService.WriteResult.UPDATED) {
                log.warn("Hot conversation snapshot rejected, conversationId={}, result={}",
                        context.getConversationId(), result);
            }
        } catch (RuntimeException failure) {
            log.warn("Failed to write hot conversation snapshot, conversationId={}",
                    context.getConversationId(), failure);
        }
    }

    private static boolean isValid(HotConversationSnapshot value, Long conversationId, List<Long> scope) {
        if (value == null || value.schemaVersion() != HotConversationSnapshot.CURRENT_SCHEMA_VERSION
                || !conversationId.equals(value.conversationId()) || !scope.equals(value.knowledgeBaseIds())
                || value.summaryCoveredThroughTurn() < 0
                || value.summaryCoveredThroughTurn() > value.totalCompletedTurns()
                || value.recentTurns().size() > value.totalCompletedTurns()) {
            return false;
        }
        return RedisHotConversationSnapshotService.scopeFingerprint(scope).equals(value.scopeFingerprint());
    }

    private static ConversationContext toContext(HotConversationSnapshot value) {
        return ConversationContext.builder()
                .conversationId(value.conversationId())
                .summary(value.summary())
                .recentTurns(value.recentTurns())
                .updatedAt(value.updatedAt().toString())
                .build();
    }

    private static List<Long> immutableScope(List<Long> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record RebuiltContext(ConversationContext context, long totalCompletedTurns) { }
}
