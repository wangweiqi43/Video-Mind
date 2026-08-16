package com.videomind.module.task.service;

import java.time.Duration;

public interface ProcessingTaskStateMachine {
    LeaseResult acquire(Long taskId, String owner, String stage, Duration leaseDuration);

    boolean renew(Long taskId, String owner, long expectedVersion, Duration leaseDuration);

    boolean updateStage(Long taskId, String owner, String stage);

    String currentStage(Long taskId);

    boolean succeed(Long taskId, String owner, long expectedVersion, String finalStage);

    boolean retry(Long taskId, String owner, long expectedVersion, String stage,
                  Duration delay, String errorCode, String errorMessage);

    boolean fail(Long taskId, String owner, long expectedVersion, String stage,
                 String errorCode, String errorMessage);

    CancelRequestResult requestCancel(Long taskId, Long userId);

    boolean cancellationRequested(Long taskId);

    boolean cancel(Long taskId, String owner);

    enum LeaseStatus {
        ACQUIRED,
        BUSY,
        NOT_DUE,
        CANCELLATION_REQUESTED,
        TERMINAL,
        RETRY_EXHAUSTED,
        NOT_FOUND
    }

    record LeaseResult(LeaseStatus status, long stateVersion) {
    }

    enum CancelRequestStatus {
        CANCEL_REQUESTED,
        CANCELLED,
        TERMINAL,
        CONFLICT,
        NOT_FOUND,
        FORBIDDEN
    }

    record CancelRequestResult(CancelRequestStatus status, long stateVersion) {
    }
}
