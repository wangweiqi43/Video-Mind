package com.videomind.module.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskCancellationGuard {
    private final ProcessingTaskMapper tasks;

    public void checkProcessingTask(Long processingTaskId) {
        throwIfCancelled(tasks.selectById(processingTaskId));
    }

    public void checkVideoTask(Long taskRecordId) {
        ProcessingTask task = tasks.selectOne(Wrappers.<ProcessingTask>lambdaQuery()
                .eq(ProcessingTask::getTaskType, ProcessingTaskType.VIDEO_ANALYSIS)
                .eq(ProcessingTask::getBusinessId, taskRecordId)
                .orderByDesc(ProcessingTask::getCreatedTime)
                .last("LIMIT 1"));
        throwIfCancelled(task);
    }

    private static void throwIfCancelled(ProcessingTask task) {
        if (task != null && (task.getState() == ProcessingTaskState.CANCEL_REQUESTED
                || task.getState() == ProcessingTaskState.CANCELLED)) {
            throw new TaskCancellationException();
        }
    }
}
