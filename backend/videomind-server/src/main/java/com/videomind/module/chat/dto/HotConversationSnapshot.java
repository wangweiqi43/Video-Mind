package com.videomind.module.chat.dto;

import java.time.Instant;
import java.util.List;

public record HotConversationSnapshot(
        int schemaVersion,
        Long conversationId,
        ConversationContext.SummarySnapshot summary,
        long summaryCoveredThroughTurn,
        long totalCompletedTurns,
        List<Long> knowledgeBaseIds,
        String scopeFingerprint,
        List<ConversationTurn> recentTurns,
        Instant updatedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public HotConversationSnapshot {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        scopeFingerprint = scopeFingerprint == null ? "" : scopeFingerprint;
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }
}
