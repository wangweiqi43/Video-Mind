package com.videomind.module.knowledge.repository.redis;

import com.videomind.common.exception.BizException;
import com.videomind.config.KnowledgeProperties;
import com.videomind.module.knowledge.dto.KnowledgeChunk;
import com.videomind.module.knowledge.repository.KnowledgeVectorRepository;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class RedisKnowledgeVectorRepository implements KnowledgeVectorRepository {

    private final KnowledgeProperties knowledgeProperties;
    private final RedisConnectionFactory redisConnectionFactory;
    private final StringRedisTemplate stringRedisTemplate;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    @Override
    public void saveChunks(Long taskId, List<KnowledgeChunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new BizException(500, "知识片段数量和向量数量不一致");
        }
        ensureIndex();
        deleteTaskChunks(taskId);

        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            for (int i = 0; i < chunks.size(); i++) {
                KnowledgeChunk chunk = chunks.get(i);
                String key = chunkKey(chunk.getTaskId(), chunk.getChunkIndex());
                connection.hMSet(bytes(key), buildHash(chunk, embeddings.get(i)));
                stringRedisTemplate.expire(key, Duration.ofSeconds(knowledgeProperties.getTtlSeconds()));
            }
        } catch (Exception ex) {
            throw new BizException(500, "写入 Redisearch 向量失败：" + ex.getMessage());
        }
    }

    @Override
    public long countChunks(Long taskId) {
        Set<String> keys = stringRedisTemplate.keys(taskKeyPattern(taskId));
        return keys == null ? 0 : keys.size();
    }

    @Override
    public void deleteChunks(Long taskId) {
        deleteTaskChunks(taskId);
    }

    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            try {
                connection.execute("FT.INFO", bytes(knowledgeProperties.getIndexName()));
                indexReady.set(true);
                return;
            } catch (Exception ignored) {
                // Index does not exist yet; create it below.
            }
            connection.execute("FT.CREATE",
                    bytes(knowledgeProperties.getIndexName()),
                    bytes("ON"), bytes("HASH"),
                    bytes("PREFIX"), bytes("1"), bytes(knowledgeProperties.getKeyPrefix()),
                    bytes("SCHEMA"),
                    bytes("userId"), bytes("NUMERIC"), bytes("SORTABLE"),
                    bytes("videoId"), bytes("NUMERIC"), bytes("SORTABLE"),
                    bytes("taskId"), bytes("NUMERIC"), bytes("SORTABLE"),
                    bytes("chunkType"), bytes("TAG"),
                    bytes("chunkIndex"), bytes("NUMERIC"), bytes("SORTABLE"),
                    bytes("chunkText"), bytes("TEXT"),
                    bytes("createdTime"), bytes("TAG"),
                    bytes("embedding"), bytes("VECTOR"), bytes("FLAT"), bytes("6"),
                    bytes("TYPE"), bytes("FLOAT32"),
                    bytes("DIM"), bytes(String.valueOf(knowledgeProperties.getEmbeddingDim())),
                    bytes("DISTANCE_METRIC"), bytes("COSINE")
            );
            indexReady.set(true);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message != null && message.toLowerCase().contains("index already exists")) {
                indexReady.set(true);
                return;
            }
            log.warn("Create Redisearch index failed, continue with Redis hash vector storage. reason={}", message);
            indexReady.set(true);
        }
    }

    private void deleteTaskChunks(Long taskId) {
        Set<String> keys = stringRedisTemplate.keys(taskKeyPattern(taskId));
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private Map<byte[], byte[]> buildHash(KnowledgeChunk chunk, float[] embedding) {
        Map<byte[], byte[]> map = new LinkedHashMap<>();
        map.put(bytes("userId"), bytes(String.valueOf(chunk.getUserId())));
        map.put(bytes("videoId"), bytes(String.valueOf(chunk.getVideoId())));
        map.put(bytes("taskId"), bytes(String.valueOf(chunk.getTaskId())));
        map.put(bytes("chunkType"), bytes(chunk.getChunkType().name()));
        map.put(bytes("chunkIndex"), bytes(String.valueOf(chunk.getChunkIndex())));
        map.put(bytes("chunkText"), bytes(chunk.getChunkText()));
        map.put(bytes("createdTime"), bytes(LocalDateTime.now().toString()));
        map.put(bytes("embedding"), floatArrayToBytes(embedding));
        return map;
    }

    private String chunkKey(Long taskId, Integer chunkIndex) {
        return knowledgeProperties.getKeyPrefix() + taskId + ":" + chunkIndex;
    }

    private String taskKeyPattern(Long taskId) {
        return knowledgeProperties.getKeyPrefix() + taskId + ":*";
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] floatArrayToBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }
}
