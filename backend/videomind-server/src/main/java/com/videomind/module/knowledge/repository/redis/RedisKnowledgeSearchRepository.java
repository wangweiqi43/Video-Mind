package com.videomind.module.knowledge.repository.redis;

import com.videomind.common.enums.KnowledgeChunkType;
import com.videomind.config.KnowledgeProperties;
import com.videomind.module.knowledge.dto.KnowledgeSearchResult;
import com.videomind.module.knowledge.repository.KnowledgeSearchRepository;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisKnowledgeSearchRepository implements KnowledgeSearchRepository {

    private final KnowledgeProperties knowledgeProperties;
    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public List<KnowledgeSearchResult> search(Long userId, Long videoId, float[] queryEmbedding, int topK) {
        String query = "(@userId:[" + userId + " " + userId + "] @videoId:[" + videoId + " " + videoId
                + "])=>[KNN " + topK + " @embedding $vec AS score]";
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            Object raw = connection.execute("FT.SEARCH",
                    bytes(knowledgeProperties.getIndexName()),
                    bytes(query),
                    bytes("PARAMS"), bytes("2"), bytes("vec"), floatArrayToBytes(queryEmbedding),
                    bytes("RETURN"), bytes("7"),
                    bytes("videoId"), bytes("taskId"), bytes("chunkType"), bytes("chunkIndex"),
                    bytes("chunkText"), bytes("score"), bytes("userId"),
                    bytes("SORTBY"), bytes("score"),
                    bytes("DIALECT"), bytes("2")
            );
            return parseResults(raw);
        } catch (Exception ex) {
            log.warn("Vector search failed, fallback to metadata search. reason={}", ex.getMessage());
            return fallbackSearch(userId, videoId, queryEmbedding, topK);
        }
    }

    private List<KnowledgeSearchResult> fallbackSearch(Long userId, Long videoId, float[] queryEmbedding, int topK) {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            Set<byte[]> keys = connection.keys(bytes(knowledgeProperties.getKeyPrefix() + "*"));
            if (keys == null || keys.isEmpty()) {
                return List.of();
            }
            return keys.stream()
                    .map(connection::hGetAll)
                    .map(this::parseHashResult)
                    .filter(result -> result.getUserId() != null && result.getUserId().equals(userId))
                    .filter(result -> result.getVideoId() != null && result.getVideoId().equals(videoId))
                    .peek(result -> result.setScore(cosine(queryEmbedding, result.getScoreVector())))
                    .sorted(Comparator.comparing(KnowledgeHashResult::getScore).reversed())
                    .limit(topK)
                    .map(KnowledgeHashResult::toSearchResult)
                    .toList();
        } catch (Exception ex) {
            log.warn("Fallback knowledge search failed. reason={}", ex.getMessage());
            return List.of();
        }
    }

    private List<KnowledgeSearchResult> parseResults(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() <= 1) {
            return List.of();
        }
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (int i = 1; i + 1 < values.size(); i += 2) {
            if (!(values.get(i + 1) instanceof List<?> fields)) {
                continue;
            }
            Map<String, String> map = toMap(fields);
            results.add(KnowledgeSearchResult.builder()
                    .userId(parseLong(map.get("userId")))
                    .videoId(parseLong(map.get("videoId")))
                    .taskId(parseLong(map.get("taskId")))
                    .chunkType(parseChunkType(map.get("chunkType")))
                    .chunkIndex(parseInteger(map.get("chunkIndex")))
                    .chunkText(map.get("chunkText"))
                    .score(parseDouble(map.get("score")))
                    .build());
        }
        return results;
    }

    private Map<String, String> toMap(List<?> fields) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < fields.size(); i += 2) {
            map.put(asString(fields.get(i)), asString(fields.get(i + 1)));
        }
        return map;
    }

    private KnowledgeHashResult parseHashResult(Map<byte[], byte[]> hash) {
        Map<String, byte[]> rawMap = new LinkedHashMap<>();
        hash.forEach((key, value) -> rawMap.put(asString(key), value));
        return KnowledgeHashResult.builder()
                .userId(parseLong(asString(rawMap.get("userId"))))
                .videoId(parseLong(asString(rawMap.get("videoId"))))
                .taskId(parseLong(asString(rawMap.get("taskId"))))
                .chunkType(parseChunkType(asString(rawMap.get("chunkType"))))
                .chunkIndex(parseInteger(asString(rawMap.get("chunkIndex"))))
                .chunkText(asString(rawMap.get("chunkText")))
                .scoreVector(bytesToFloatArray(rawMap.get("embedding")))
                .build();
    }

    private String asString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? null : String.valueOf(value);
    }

    private KnowledgeChunkType parseChunkType(String value) {
        if (value == null) {
            return null;
        }
        return KnowledgeChunkType.valueOf(value);
    }

    private Long parseLong(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    private Integer parseInteger(String value) {
        return value == null ? null : Integer.parseInt(value);
    }

    private Double parseDouble(String value) {
        return value == null ? null : Double.parseDouble(value);
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

    private float[] bytesToFloatArray(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    @lombok.Data
    @lombok.Builder
    private static class KnowledgeHashResult {

        private Long userId;
        private Long videoId;
        private Long taskId;
        private KnowledgeChunkType chunkType;
        private Integer chunkIndex;
        private String chunkText;
        private Double score;
        private float[] scoreVector;

        private KnowledgeSearchResult toSearchResult() {
            return KnowledgeSearchResult.builder()
                    .userId(userId)
                    .videoId(videoId)
                    .taskId(taskId)
                    .chunkType(chunkType)
                    .chunkIndex(chunkIndex)
                    .chunkText(chunkText)
                    .score(score)
                    .build();
        }
    }
}
