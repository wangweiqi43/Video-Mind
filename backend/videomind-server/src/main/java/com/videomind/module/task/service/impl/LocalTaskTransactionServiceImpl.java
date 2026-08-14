package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TaskTransactionContext;
import com.videomind.module.task.service.LocalTaskTransactionService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LocalTaskTransactionServiceImpl implements LocalTaskTransactionService {
    private final ProcessingTaskMapper taskMapper;
    private final MqTransactionEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDispatchResult createOrReuse(TaskTransactionContext context) {
        TaskCreateCommand command = context.getCommand();
        LocalDateTime now = LocalDateTime.now();
        ProcessingTask candidate = new ProcessingTask();
        candidate.setId(context.getRequestedTaskId());
        candidate.setEventId(context.getEventId());
        candidate.setUserId(command.userId());
        candidate.setTaskType(command.taskType());
        candidate.setBusinessId(command.businessId());
        candidate.setBusinessFingerprint(command.businessFingerprint());
        candidate.setActiveFingerprint(command.businessFingerprint());
        candidate.setState(ProcessingTaskState.PENDING);
        candidate.setStage(normalizeStage(command.initialStage()));
        candidate.setStateVersion(0L);
        candidate.setAttemptCount(0);
        candidate.setMaxAttempts(command.maxAttempts() > 0 ? command.maxAttempts() : 5);
        candidate.setReplayGeneration(0);
        candidate.setCreatedTime(now);
        candidate.setUpdatedTime(now);

        boolean inserted = taskMapper.insertIgnoreActive(candidate) == 1;
        ProcessingTask task = inserted ? candidate : taskMapper.selectOne(Wrappers.<ProcessingTask>lambdaQuery()
                .eq(ProcessingTask::getActiveFingerprint, command.businessFingerprint())
                .last("LIMIT 1"));
        if (task == null) {
            throw new IllegalStateException("active task disappeared after INSERT IGNORE");
        }

        MqTransactionEvent event = new MqTransactionEvent();
        event.setEventId(context.getEventId());
        event.setTaskId(task.getId());
        event.setTopic(context.getTopic());
        event.setTag(context.getTag());
        event.setTransactionState("COMMITTED");
        event.setPayloadJson(json(command));
        event.setCreatedTime(now);
        event.setUpdatedTime(now);
        eventMapper.insert(event);

        context.resolve(task.getId(), !inserted);
        return new TaskDispatchResult(context.getEventId(), task.getId(), !inserted);
    }

    private String json(TaskCreateCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("task payload cannot be serialized", failure);
        }
    }

    private static String normalizeStage(String value) {
        if (value == null || value.isBlank()) {
            return "START";
        }
        String normalized = value.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
