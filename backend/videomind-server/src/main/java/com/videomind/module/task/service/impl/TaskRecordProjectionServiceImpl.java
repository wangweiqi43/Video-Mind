package com.videomind.module.task.service.impl;

import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.common.enums.TaskStatus;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.service.TaskRecordProjectionService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskRecordProjectionServiceImpl implements TaskRecordProjectionService {
    private final ProcessingTaskMapper processingTasks;
    private final TaskRecordMapper taskRecords;

    @Override
    public void project(Long processingTaskId) {
        ProcessingTask source = processingTasks.selectById(processingTaskId);
        if (source == null || source.getTaskType() != ProcessingTaskType.VIDEO_ANALYSIS) {
            return;
        }
        TaskRecord target = taskRecords.selectById(source.getBusinessId());
        if (target == null || !source.getUserId().equals(target.getUserId())) {
            throw new IllegalStateException("VIDEO_TASK_PROJECTION_TARGET_MISSING");
        }
        TaskStatus status = status(source.getState());
        target.setTaskStatus(status);
        target.setUpdatedTime(LocalDateTime.now());
        if (status == TaskStatus.PROCESSING && target.getStartedTime() == null) {
            target.setStartedTime(source.getStartedTime() == null ? LocalDateTime.now() : source.getStartedTime());
        }
        if (status == TaskStatus.RETRYING) {
            target.setRetryCount(Math.max(value(target.getRetryCount()), value(source.getAttemptCount())));
            target.setErrorMessage(source.getErrorMessage());
        } else if (status == TaskStatus.FAILED) {
            target.setRetryCount(Math.max(value(target.getRetryCount()), Math.max(0, value(source.getAttemptCount()) - 1)));
            target.setErrorMessage(source.getErrorMessage() == null ? source.getErrorCode() : source.getErrorMessage());
            target.setFinishedTime(source.getFinishedTime() == null ? LocalDateTime.now() : source.getFinishedTime());
        } else if (status == TaskStatus.SUCCESS) {
            target.setErrorMessage(null);
            target.setFinishedTime(source.getFinishedTime() == null ? LocalDateTime.now() : source.getFinishedTime());
        } else {
            target.setErrorMessage(null);
            target.setFinishedTime(null);
        }
        taskRecords.updateById(target);
    }

    private static TaskStatus status(ProcessingTaskState state) {
        if (state == null) {
            throw new IllegalStateException("PROCESSING_TASK_STATE_MISSING");
        }
        return switch (state) {
            case PENDING -> TaskStatus.PENDING;
            case PROCESSING -> TaskStatus.PROCESSING;
            case RETRY_WAIT -> TaskStatus.RETRYING;
            case SUCCESS -> TaskStatus.SUCCESS;
            case FAILED, DEAD -> TaskStatus.FAILED;
        };
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
