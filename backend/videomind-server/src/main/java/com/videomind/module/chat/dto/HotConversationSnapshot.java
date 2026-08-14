package com.videomind.module.chat.dto;

import java.time.Instant;
import java.util.List;

public record HotConversationSnapshot(
        Long conversationId,
        String summary,
        long summaryCoveredThroughTurn,
        long totalCompletedTurns,
        List<Long> knowledgeBaseIds,
        Instant updatedAt) {

    public HotConversationSnapshot {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        summary = summary == null ? "" : summary;
    }
}
