package com.videomind.module.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.service.HotConversationSnapshotService.WriteResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisHotConversationSnapshotServiceTest {
    @Test
    void rejectsOlderCompletedTurnSnapshot() {
        HotConversationSnapshot existing = snapshot(12, 8, List.of(1L, 2L));
        HotConversationSnapshot stale = snapshot(11, 9, List.of(1L, 2L));
        assertThat(RedisHotConversationSnapshotService.decide(existing, stale))
                .isEqualTo(WriteResult.STALE_REJECTED);
    }

    @Test
    void rejectsSummaryBoundaryRegressionAtSameCompletedTurn() {
        HotConversationSnapshot existing = snapshot(12, 8, List.of(1L, 2L));
        HotConversationSnapshot stale = snapshot(12, 7, List.of(1L, 2L));
        assertThat(RedisHotConversationSnapshotService.decide(existing, stale))
                .isEqualTo(WriteResult.STALE_REJECTED);
    }

    @Test
    void knowledgeScopeIsImmutableForConversationLifetime() {
        HotConversationSnapshot existing = snapshot(12, 8, List.of(10L, 20L));
        HotConversationSnapshot changed = snapshot(13, 8, List.of(10L, 30L));
        assertThat(RedisHotConversationSnapshotService.decide(existing, changed))
                .isEqualTo(WriteResult.SCOPE_MISMATCH);
    }

    @Test
    void acceptsForwardOnlySnapshotWithSameOrderedScope() {
        HotConversationSnapshot existing = snapshot(12, 8, List.of(10L, 20L));
        HotConversationSnapshot next = snapshot(13, 9, List.of(10L, 20L));
        assertThat(RedisHotConversationSnapshotService.decide(existing, next)).isEqualTo(WriteResult.UPDATED);
        assertThat(RedisHotConversationSnapshotService.WRITE_LUA).contains("HSET", "EXPIRE", "scopeFingerprint");
    }

    private static HotConversationSnapshot snapshot(long turns, long boundary, List<Long> scope) {
        return new HotConversationSnapshot(1L, "summary", boundary, turns, scope, Instant.parse("2026-08-14T00:00:00Z"));
    }
}
