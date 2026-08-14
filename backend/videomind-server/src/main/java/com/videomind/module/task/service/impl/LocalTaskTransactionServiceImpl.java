package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.common.enums.TaskStatus;
import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
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
    private final TaskRecordMapper taskRecordMapper;
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

        Long businessId = task.getBusinessId();
        if (inserted && command.taskType() == ProcessingTaskType.VIDEO_ANALYSIS) {
            businessId = createVideoTask(command, now);
            if (taskMapper.bindBusinessId(task.getId(), command.businessId(), businessId, now) != 1) {
                throw new IllegalStateException("video processing task business binding was lost");
            }
            task.setBusinessId(businessId);
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

        context.resolve(task.getId(), businessId, !inserted);
        return new TaskDispatchResult(context.getEventId(), task.getId(), businessId, !inserted);
    }

    private Long createVideoTask(TaskCreateCommand command, LocalDateTime now) {
        Object md5Value = command.payload() == null ? null : command.payload().get("videoMd5");
        String videoMd5 = md5Value == null ? null : md5Value.toString().trim();
        if (videoMd5 == null || videoMd5.isBlank()) {
            throw new IllegalArgumentException("video analysis command is missing videoMd5");
        }
        TaskRecord record = new TaskRecord();
        record.setUserId(command.userId());
        record.setVideoId(command.businessId());
        record.setVideoMd5(videoMd5);
        record.setTaskStatus(TaskStatus.PENDING);
        record.setRetryCount(0);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        record.setDeleted(0);
        taskRecordMapper.insert(record);
        if (record.getId() == null) {
            throw new IllegalStateException("video task record id was not generated");
        }
        return record.getId();
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
