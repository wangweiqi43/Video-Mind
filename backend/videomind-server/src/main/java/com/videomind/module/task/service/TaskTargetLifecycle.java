package com.videomind.module.task.service;

import com.videomind.module.task.mq.TaskCreateCommand;
import java.time.LocalDateTime;

public interface TaskTargetLifecycle {
    void onTaskCreated(TaskCreateCommand command, LocalDateTime now);
}
