package com.videomind.module.knowledge.repository.redis;

import com.videomind.config.KnowledgeProperties;
import com.videomind.module.knowledge.dto.KnowledgeStatusResponse;
import com.videomind.module.knowledge.repository.KnowledgeStatusRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisKnowledgeStatusRepository implements KnowledgeStatusRepository {

    private final KnowledgeProperties knowledgeProperties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void saveStatus(Long taskId, boolean vectorized, String status, String message, int chunkCount) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("taskId", String.valueOf(taskId));
        data.put("vectorized", String.valueOf(vectorized));
        data.put("status", status);
        data.put("message", message);
        data.put("chunkCount", String.valueOf(chunkCount));
        data.put("updatedTime", LocalDateTime.now().toString());
        String key = statusKey(taskId);
        stringRedisTemplate.opsForHash().putAll(key, data);
        stringRedisTemplate.expire(key, Duration.ofSeconds(knowledgeProperties.getTtlSeconds()));
    }

    @Override
    public KnowledgeStatusResponse getStatus(Long taskId) {
        Map<Object, Object> data = stringRedisTemplate.opsForHash().entries(statusKey(taskId));
        if (data.isEmpty()) {
            return KnowledgeStatusResponse.builder()
                    .taskId(taskId)
                    .vectorized(false)
                    .status("NOT_VECTORIZED")
                    .message("该任务尚未加入知识库。")
                    .build();
        }
        return KnowledgeStatusResponse.builder()
                .taskId(taskId)
                .vectorized(Boolean.parseBoolean(String.valueOf(data.get("vectorized"))))
                .status(String.valueOf(data.get("status")))
                .message(String.valueOf(data.get("message")))
                .chunkCount(parseInt(data.get("chunkCount")))
                .updatedTime(String.valueOf(data.get("updatedTime")))
                .build();
    }

    @Override
    public void deleteStatus(Long taskId) {
        stringRedisTemplate.delete(statusKey(taskId));
    }

    private String statusKey(Long taskId) {
        return knowledgeProperties.getTaskStatusPrefix() + taskId + ":status";
    }

    private Integer parseInt(Object value) {
        if (value == null) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
