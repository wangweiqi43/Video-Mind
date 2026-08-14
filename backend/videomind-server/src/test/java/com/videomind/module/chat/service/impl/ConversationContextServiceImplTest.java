package com.videomind.module.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.ConversationTurn;
import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.service.ConversationSummaryService;
import com.videomind.module.chat.service.HotConversationSnapshotService;
import com.videomind.module.chat.support.ConversationTurnAssembler;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationContextServiceImplTest {
    private final HotConversationSnapshotService hotSnapshots = mock(HotConversationSnapshotService.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final ConversationSummaryService summaries = mock(ConversationSummaryService.class);
    private final ConversationTurnAssembler turns = mock(ConversationTurnAssembler.class);
    private final ConversationContextServiceImpl service = new ConversationContextServiceImpl(
            hotSnapshots, messages, summaries, turns);

    @BeforeEach
    void acceptWrites() {
        when(hotSnapshots.write(any())).thenReturn(HotConversationSnapshotService.WriteResult.UPDATED);
    }

    @Test
    void servesValidatedRecentTurnsDirectlyFromCacheRedis() {
        List<Long> scope = List.of(10L, 20L);
        ConversationTurn recent = turn(101L, 102L);
        when(hotSnapshots.get(7L)).thenReturn(Optional.of(snapshot(7L, scope, 1, 2, List.of(recent))));

        ConversationContext context = service.getContext(7L, 99L, scope);

        assertThat(context.getConversationId()).isEqualTo(7L);
        assertThat(context.getRecentTurns()).containsExactly(recent);
        assertThat(context.getSummary().getSummaryText()).isEqualTo("summary");
        verifyNoInteractions(messages, summaries, turns);
    }

    @Test
    void rebuildsCacheMissFromMysqlAndWritesCompleteSnapshot() {
        List<Long> scope = List.of(10L, 20L);
        ConversationTurn recent = turn(201L, 202L);
        when(hotSnapshots.get(7L)).thenReturn(Optional.empty());
        when(messages.selectList(any())).thenReturn(List.of());
        when(messages.selectCount(any())).thenReturn(1L);
        when(turns.assemble(any())).thenReturn(List.of(recent));

        ConversationContext context = service.getContext(7L, 99L, scope);

        assertThat(context.getRecentTurns()).containsExactly(recent);
        ArgumentCaptor<HotConversationSnapshot> written = ArgumentCaptor.forClass(HotConversationSnapshot.class);
        verify(hotSnapshots).write(written.capture());
        assertThat(written.getValue().schemaVersion()).isEqualTo(HotConversationSnapshot.CURRENT_SCHEMA_VERSION);
        assertThat(written.getValue().totalCompletedTurns()).isEqualTo(1);
        assertThat(written.getValue().knowledgeBaseIds()).containsExactly(10L, 20L);
        assertThat(written.getValue().scopeFingerprint())
                .isEqualTo(RedisHotConversationSnapshotService.scopeFingerprint(scope));
        assertThat(written.getValue().recentTurns()).containsExactly(recent);
    }

    @Test
    void rejectsCachedScopeMismatchAndRebuildsFromMysql() {
        when(hotSnapshots.get(7L)).thenReturn(Optional.of(snapshot(7L, List.of(10L), 0, 0, List.of())));
        when(hotSnapshots.write(any())).thenReturn(HotConversationSnapshotService.WriteResult.SCOPE_MISMATCH);
        when(messages.selectList(any())).thenReturn(List.of());
        when(messages.selectCount(any())).thenReturn(0L);
        when(turns.assemble(any())).thenReturn(List.of());

        service.getContext(7L, 99L, List.of(10L, 20L));

        verify(messages).selectList(any());
        ArgumentCaptor<HotConversationSnapshot> written = ArgumentCaptor.forClass(HotConversationSnapshot.class);
        verify(hotSnapshots).write(written.capture());
        assertThat(written.getValue().knowledgeBaseIds()).containsExactly(10L, 20L);
        verify(hotSnapshots, never()).evict(7L);
    }

    @Test
    void fallsBackToMysqlWhenCacheRedisReadFails() {
        when(hotSnapshots.get(7L)).thenThrow(new IllegalStateException("redis unavailable"));
        when(messages.selectList(any())).thenReturn(List.of());
        when(messages.selectCount(any())).thenReturn(0L);
        when(turns.assemble(any())).thenReturn(List.of());

        ConversationContext context = service.getContext(7L, 99L, List.of(10L));

        assertThat(context.getConversationId()).isEqualTo(7L);
        verify(messages).selectList(any());
    }

    @Test
    void returnsMysqlContextWhenCacheRedisWriteFails() {
        when(hotSnapshots.get(7L)).thenReturn(Optional.empty());
        when(hotSnapshots.write(any())).thenThrow(new IllegalStateException("redis unavailable"));
        when(messages.selectList(any())).thenReturn(List.of());
        when(messages.selectCount(any())).thenReturn(0L);
        when(turns.assemble(any())).thenReturn(List.of());

        ConversationContext context = service.getContext(7L, 99L, List.of(10L));

        assertThat(context.getConversationId()).isEqualTo(7L);
        verify(messages).selectList(any());
    }

    private static HotConversationSnapshot snapshot(Long id, List<Long> scope, long boundary, long total,
                                                     List<ConversationTurn> recentTurns) {
        ConversationContext.SummarySnapshot summary = ConversationContext.SummarySnapshot.builder()
                .summaryText("summary").coveredTurnCount(Math.toIntExact(boundary)).build();
        return new HotConversationSnapshot(HotConversationSnapshot.CURRENT_SCHEMA_VERSION, id, summary,
                boundary, total, scope, RedisHotConversationSnapshotService.scopeFingerprint(scope), recentTurns,
                Instant.parse("2026-08-14T00:00:00Z"));
    }

    private static ConversationTurn turn(Long userId, Long assistantId) {
        return ConversationTurn.builder().userMessageId(userId).assistantMessageId(assistantId)
                .question("question").answer("answer").build();
    }
}
