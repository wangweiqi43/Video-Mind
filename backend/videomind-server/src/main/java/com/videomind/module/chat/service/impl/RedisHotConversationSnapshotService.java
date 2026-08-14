package com.videomind.module.chat.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.CacheRedisProperties;
import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.dto.ConversationContext;
import com.videomind.module.chat.dto.ConversationTurn;
import com.videomind.module.chat.service.HotConversationSnapshotService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RedisHotConversationSnapshotService implements HotConversationSnapshotService {
    static final String WRITE_LUA = """
            local oldTurns = tonumber(redis.call('HGET', KEYS[1], 'totalCompletedTurns') or '-1')
            local oldBoundary = tonumber(redis.call('HGET', KEYS[1], 'summaryCoveredThroughTurn') or '-1')
            local oldScope = redis.call('HGET', KEYS[1], 'scopeFingerprint')
            if oldScope and oldScope ~= '' and oldScope ~= ARGV[1] then return -1 end
            local newTurns = tonumber(ARGV[5])
            local newBoundary = tonumber(ARGV[4])
            if oldTurns > newTurns or (oldTurns == newTurns and oldBoundary > newBoundary) then return 0 end
            redis.call('HSET', KEYS[1],
              'scopeFingerprint', ARGV[1], 'schemaVersion', ARGV[2],
              'summary', ARGV[3], 'summaryCoveredThroughTurn', ARGV[4],
              'totalCompletedTurns', ARGV[5], 'knowledgeBaseIds', ARGV[6],
              'recentTurns', ARGV[7], 'updatedAt', ARGV[8])
            redis.call('EXPIRE', KEYS[1], ARGV[9])
            return 1
            """;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CacheRedisProperties properties;
    private final DefaultRedisScript<Long> writeScript = new DefaultRedisScript<>(WRITE_LUA, Long.class);

    public RedisHotConversationSnapshotService(
            @Qualifier("hotContextRedisTemplate") StringRedisTemplate redis,
            ObjectMapper objectMapper, CacheRedisProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public WriteResult write(HotConversationSnapshot snapshot) {
        validate(snapshot);
        try {
            String ids = objectMapper.writeValueAsString(snapshot.knowledgeBaseIds());
            String summary = objectMapper.writeValueAsString(snapshot.summary());
            String recentTurns = objectMapper.writeValueAsString(snapshot.recentTurns());
            Long result = redis.execute(writeScript, List.of(key(snapshot.conversationId())),
                    snapshot.scopeFingerprint(), Integer.toString(snapshot.schemaVersion()), summary,
                    Long.toString(snapshot.summaryCoveredThroughTurn()),
                    Long.toString(snapshot.totalCompletedTurns()), ids, recentTurns,
                    snapshot.updatedAt().toString(), Long.toString(Math.max(60, properties.getContextTtlSeconds())));
            return result != null && result == 1 ? WriteResult.UPDATED
                    : result != null && result == -1 ? WriteResult.SCOPE_MISMATCH : WriteResult.STALE_REJECTED;
        } catch (Exception failure) {
            throw new IllegalStateException("HOT_CONTEXT_WRITE_FAILED", failure);
        }
    }

    @Override
    public Optional<HotConversationSnapshot> get(Long conversationId) {
        try {
            Map<Object, Object> value = redis.opsForHash().entries(key(conversationId));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            List<Long> ids = objectMapper.readValue(text(value, "knowledgeBaseIds"), new TypeReference<>() { });
            ConversationContext.SummarySnapshot summary = objectMapper.readValue(text(value, "summary"),
                    ConversationContext.SummarySnapshot.class);
            List<ConversationTurn> recentTurns = objectMapper.readValue(text(value, "recentTurns"),
                    new TypeReference<>() { });
            HotConversationSnapshot snapshot = new HotConversationSnapshot(integer(value, "schemaVersion"),
                    conversationId, summary, number(value, "summaryCoveredThroughTurn"),
                    number(value, "totalCompletedTurns"), ids, text(value, "scopeFingerprint"), recentTurns,
                    Instant.parse(text(value, "updatedAt")));
            validate(snapshot);
            redis.expire(key(conversationId), Duration.ofSeconds(Math.max(60, properties.getContextTtlSeconds())));
            return Optional.of(snapshot);
        } catch (Exception failure) {
            log.warn("Hot conversation snapshot read failed, conversationId={}", conversationId, failure);
            return Optional.empty();
        }
    }

    @Override
    public void evict(Long conversationId) {
        redis.delete(key(conversationId));
    }

    static WriteResult decide(HotConversationSnapshot existing, HotConversationSnapshot incoming) {
        if (existing != null && !existing.scopeFingerprint().equals(incoming.scopeFingerprint())) {
            return WriteResult.SCOPE_MISMATCH;
        }
        if (existing != null && (existing.totalCompletedTurns() > incoming.totalCompletedTurns()
                || existing.totalCompletedTurns() == incoming.totalCompletedTurns()
                && existing.summaryCoveredThroughTurn() > incoming.summaryCoveredThroughTurn())) {
            return WriteResult.STALE_REJECTED;
        }
        return WriteResult.UPDATED;
    }

    static String scopeFingerprint(List<Long> ids) {
        String orderedIds = ids.stream().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(orderedIds.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void validate(HotConversationSnapshot value) {
        if (value == null || value.conversationId() == null || value.updatedAt() == null
                || value.schemaVersion() != HotConversationSnapshot.CURRENT_SCHEMA_VERSION
                || value.summaryCoveredThroughTurn() < 0 || value.totalCompletedTurns() < 0
                || value.summaryCoveredThroughTurn() > value.totalCompletedTurns()
                || !scopeFingerprint(value.knowledgeBaseIds()).equals(value.scopeFingerprint())
                || value.recentTurns().size() > value.totalCompletedTurns()) {
            throw new IllegalArgumentException("invalid hot conversation snapshot");
        }
    }

    private static String text(Map<Object, Object> value, String key) {
        Object item = value.get(key);
        return item == null ? "" : item.toString();
    }

    private static long number(Map<Object, Object> value, String key) {
        return Long.parseLong(text(value, key));
    }

    private static int integer(Map<Object, Object> value, String key) {
        return Integer.parseInt(text(value, key));
    }

    private static String key(Long conversationId) {
        return "hot:conversation:" + conversationId;
    }
}
