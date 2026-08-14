package com.videomind.module.task.service;

public class TaskCancellationException extends RuntimeException {
    public TaskCancellationException() {
        super("TASK_CANCELLATION_REQUESTED");
    }
}
