package com.videomind.module.task.service;

import java.time.Duration;

public interface ProcessingTaskStateMachine {
    LeaseResult acquire(Long taskId, String owner, String stage, Duration leaseDuration);

    boolean renew(Long taskId, String owner, long expectedVersion, Duration leaseDuration);

    boolean succeed(Long taskId, String owner, long expectedVersion, String finalStage);

    boolean retry(Long taskId, String owner, long expectedVersion, String stage,
                  Duration delay, String errorCode, String errorMessage);

    boolean fail(Long taskId, String owner, long expectedVersion, String stage,
                 String errorCode, String errorMessage);

    enum LeaseStatus {
        ACQUIRED,
        BUSY,
        NOT_DUE,
        TERMINAL,
        RETRY_EXHAUSTED,
        NOT_FOUND
    }

    record LeaseResult(LeaseStatus status, long stateVersion) {
    }
}
