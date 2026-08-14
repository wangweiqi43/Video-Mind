package com.videomind.module.task.mq;

import lombok.Getter;

@Getter
public final class TaskTransactionContext {
    private final String eventId;
    private final Long requestedTaskId;
    private final String topic;
    private final String tag;
    private final TaskCreateCommand command;
    private Long resolvedProcessingTaskId;
    private Long resolvedBusinessId;
    private boolean reused;

    public TaskTransactionContext(String eventId, Long requestedTaskId, String topic, String tag,
                                  TaskCreateCommand command) {
        this.eventId = eventId;
        this.requestedTaskId = requestedTaskId;
        this.topic = topic;
        this.tag = tag;
        this.command = command;
    }

    public void resolve(Long processingTaskId, Long businessId, boolean reused) {
        this.resolvedProcessingTaskId = processingTaskId;
        this.resolvedBusinessId = businessId;
        this.reused = reused;
    }
}
