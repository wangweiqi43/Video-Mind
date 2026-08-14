package com.videomind.module.chat.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.CacheRedisProperties;
import com.videomind.module.chat.dto.HotConversationSnapshot;
import com.videomind.module.chat.service.HotConversationSnapshotService;
import java.time.Instant;
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
            local newTurns = tonumber(ARGV[4])
            local newBoundary = tonumber(ARGV[3])
            if oldTurns > newTurns or (oldTurns == newTurns and oldBoundary > newBoundary) then return 0 end
            redis.call('HSET', KEYS[1],
              'scopeFingerprint', ARGV[1], 'summary', ARGV[2],
              'summaryCoveredThroughTurn', ARGV[3], 'totalCompletedTurns', ARGV[4],
              'knowledgeBaseIds', ARGV[5], 'updatedAt', ARGV[6])
            redis.call('EXPIRE', KEYS[1], ARGV[7])
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
            Long result = redis.execute(writeScript, List.of(key(snapshot.conversationId())),
                    scopeFingerprint(snapshot.knowledgeBaseIds()), snapshot.summary(),
                    Long.toString(snapshot.summaryCoveredThroughTurn()),
                    Long.toString(snapshot.totalCompletedTurns()), ids,
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
            return Optional.of(new HotConversationSnapshot(conversationId, text(value, "summary"),
                    number(value, "summaryCoveredThroughTurn"), number(value, "totalCompletedTurns"), ids,
                    Instant.parse(text(value, "updatedAt"))));
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
        if (existing != null && !scopeFingerprint(existing.knowledgeBaseIds())
                .equals(scopeFingerprint(incoming.knowledgeBaseIds()))) {
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
        return ids.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }

    private static void validate(HotConversationSnapshot value) {
        if (value == null || value.conversationId() == null || value.updatedAt() == null
                || value.summaryCoveredThroughTurn() < 0 || value.totalCompletedTurns() < 0
                || value.summaryCoveredThroughTurn() > value.totalCompletedTurns()) {
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

    private static String key(Long conversationId) {
        return "hot:conversation:" + conversationId;
    }
}
