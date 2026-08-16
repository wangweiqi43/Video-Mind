package com.videomind.module.task.service;

import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.task.mq.TaskCreateCommand;

public interface ProcessingTaskHandler {
    ProcessingTaskType type();

    String handle(TaskExecutionContext context) throws Exception;

    record TaskExecutionContext(Long taskId, String eventId, String owner, TaskCreateCommand command) {
        public TaskExecutionContext(Long taskId, String eventId, TaskCreateCommand command) {
            this(taskId, eventId, "test-owner", command);
        }
    }
}
