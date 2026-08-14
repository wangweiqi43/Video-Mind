package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.exception.BizException;
import com.videomind.module.task.entity.TaskCheckpoint;
import com.videomind.module.task.mapper.TaskCheckpointMapper;
import com.videomind.module.task.service.TaskCheckpointService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TaskCheckpointServiceImpl implements TaskCheckpointService {
    private final TaskCheckpointMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskCheckpoint complete(Long taskId, String stage, String artifactJson, String checksum) {
        String normalizedStage = normalizeStage(stage);
        TaskCheckpoint existing = find(taskId, normalizedStage);
        if (existing != null) {
            return requireSame(existing, checksum);
        }
        LocalDateTime now = LocalDateTime.now();
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setTaskId(taskId);
        checkpoint.setStage(normalizedStage);
        checkpoint.setStatus("COMPLETED");
        checkpoint.setArtifactJson(artifactJson);
        checkpoint.setChecksum(checksum);
        checkpoint.setCompletedTime(now);
        checkpoint.setCreatedTime(now);
        checkpoint.setUpdatedTime(now);
        try {
            mapper.insert(checkpoint);
            return checkpoint;
        } catch (DuplicateKeyException concurrent) {
            TaskCheckpoint winner = find(taskId, normalizedStage);
            if (winner == null) {
                throw concurrent;
            }
            return requireSame(winner, checksum);
        }
    }

    @Override
    public boolean isCompleted(Long taskId, String stage) {
        TaskCheckpoint value = find(taskId, normalizeStage(stage));
        return value != null && "COMPLETED".equals(value.getStatus());
    }

    @Override
    public List<TaskCheckpoint> completed(Long taskId) {
        return mapper.selectList(Wrappers.<TaskCheckpoint>lambdaQuery()
                .eq(TaskCheckpoint::getTaskId, taskId)
                .eq(TaskCheckpoint::getStatus, "COMPLETED")
                .orderByAsc(TaskCheckpoint::getId));
    }

    private TaskCheckpoint find(Long taskId, String stage) {
        return mapper.selectOne(Wrappers.<TaskCheckpoint>lambdaQuery()
                .eq(TaskCheckpoint::getTaskId, taskId)
                .eq(TaskCheckpoint::getStage, stage)
                .last("LIMIT 1"));
    }

    private static TaskCheckpoint requireSame(TaskCheckpoint existing, String checksum) {
        if (!Objects.equals(existing.getChecksum(), checksum)) {
            throw new BizException(409, "同一任务阶段已存在不同校验和的产物");
        }
        return existing;
    }

    private static String normalizeStage(String stage) {
        if (!StringUtils.hasText(stage)) {
            throw new IllegalArgumentException("checkpoint stage must not be blank");
        }
        String value = stage.trim();
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
