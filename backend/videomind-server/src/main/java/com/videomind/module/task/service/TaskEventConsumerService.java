package com.videomind.module.task.service;

import com.videomind.module.task.mq.TaskEventMessage;

public interface TaskEventConsumerService {
    void consume(TaskEventMessage message);
}
