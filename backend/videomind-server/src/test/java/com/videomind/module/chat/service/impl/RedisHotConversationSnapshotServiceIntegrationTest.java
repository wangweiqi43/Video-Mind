package com.videomind.module.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.CacheRedisProperties;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.service.HotConversationSnapshotService.WriteResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisHotConversationSnapshotServiceIntegrationTest {
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisHotConversationSnapshotService service;
    private Long conversationId;

    @BeforeEach
    void connectToIsolatedCacheRedis() {
        assumeTrue(Boolean.parseBoolean(System.getenv("VIDEOMIND_CACHE_REDIS_INTEGRATION")),
                "set VIDEOMIND_CACHE_REDIS_INTEGRATION=true for the isolated Redis integration test");
        int port = Integer.parseInt(System.getenv().getOrDefault("CACHE_REDIS_PORT", "6382"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        CacheRedisProperties properties = new CacheRedisProperties();
        properties.setContextTtlSeconds(60);
        service = new RedisHotConversationSnapshotService(redis,
                new ObjectMapper().findAndRegisterModules(), properties);
        conversationId = 8_000_000_000L + Math.floorMod(System.nanoTime(), 1_000_000_000L);
    }

    @AfterEach
    void cleanUp() {
        if (redis != null && conversationId != null) {
            redis.delete(key());
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void atomicallyRejectsRegressionsAndKeepsTheHighestConcurrentSnapshot() throws Exception {
        List<Long> scope = List.of(10L, 20L);
        assertThat(service.write(snapshot(12, 8, scope))).isEqualTo(WriteResult.UPDATED);
        assertThat(service.write(snapshot(13, 7, scope))).isEqualTo(WriteResult.STALE_REJECTED);
        assertThat(service.write(snapshot(13, 8, List.of(10L, 30L)))).isEqualTo(WriteResult.SCOPE_MISMATCH);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<WriteResult>> writes = new ArrayList<>();
            for (int turn = 13; turn <= 32; turn++) {
                int value = turn;
                writes.add(CompletableFuture.supplyAsync(() -> service.write(snapshot(value, value / 4, scope)),
                        executor));
            }
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        HotConversationSnapshot stored = service.get(conversationId).orElseThrow();
        assertThat(stored.totalCompletedTurns()).isEqualTo(32);
        assertThat(stored.summaryCoveredThroughTurn()).isEqualTo(8);
        assertThat(stored.knowledgeBaseIds()).containsExactly(10L, 20L);
        assertThat(redis.getExpire(key(), TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    void evictsUnreadableSnapshotInsteadOfServingCorruptContext() {
        redis.opsForHash().putAll(key(), Map.of(
                "schemaVersion", "broken",
                "scopeFingerprint", "not-a-valid-fingerprint",
                "knowledgeBaseIds", "not-json"));

        assertThat(service.get(conversationId)).isEmpty();
        assertThat(redis.hasKey(key())).isFalse();
    }

    private HotConversationSnapshot snapshot(long turns, long boundary, List<Long> scope) {
        ConversationContext.SummarySnapshot summary = ConversationContext.SummarySnapshot.builder()
                .summaryText("summary-" + turns).coveredTurnCount(Math.toIntExact(boundary)).build();
        return new HotConversationSnapshot(HotConversationSnapshot.CURRENT_SCHEMA_VERSION, conversationId,
                summary, boundary, turns, scope, RedisHotConversationSnapshotService.scopeFingerprint(scope),
                List.of(), Instant.now());
    }

    private String key() {
        return "hot:conversation:" + conversationId;
    }
}
