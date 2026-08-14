package com.videomind.module.task.service.impl;

import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.service.ProcessingTaskStateMachine;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProcessingTaskStateMachineImpl implements ProcessingTaskStateMachine {
    private final ProcessingTaskMapper mapper;

    @Override
    public LeaseResult acquire(Long taskId, String owner, String stage, Duration leaseDuration) {
        requireOwner(owner);
        requirePositive(leaseDuration);
        ProcessingTask task = mapper.selectById(taskId);
        if (task == null) {
            return new LeaseResult(LeaseStatus.NOT_FOUND, -1);
        }
        long version = value(task.getStateVersion());
        if (task.getState().terminal()) {
            return new LeaseResult(LeaseStatus.TERMINAL, version);
        }
        LocalDateTime now = LocalDateTime.now();
        if (task.getState() == ProcessingTaskState.RETRY_WAIT && task.getNextRetryAt() != null
                && task.getNextRetryAt().isAfter(now)) {
            return new LeaseResult(LeaseStatus.NOT_DUE, version);
        }
        if (value(task.getAttemptCount()) >= value(task.getMaxAttempts())) {
            mapper.markTerminal(taskId, version, null, ProcessingTaskState.DEAD.name(), task.getStage(),
                    "RETRY_EXHAUSTED", "任务重试次数已耗尽", now);
            return new LeaseResult(LeaseStatus.RETRY_EXHAUSTED, version + 1);
        }
        if (task.getState() == ProcessingTaskState.PROCESSING && task.getLeaseExpiresAt() != null
                && !task.getLeaseExpiresAt().isBefore(now) && !owner.equals(task.getLeaseOwner())) {
            return new LeaseResult(LeaseStatus.BUSY, version);
        }
        int updated = mapper.acquireLease(taskId, version, owner, normalizeStage(stage), now,
                now.plus(leaseDuration));
        return updated == 1 ? new LeaseResult(LeaseStatus.ACQUIRED, version + 1)
                : new LeaseResult(LeaseStatus.BUSY, version);
    }

    @Override
    public boolean renew(Long taskId, String owner, long expectedVersion, Duration leaseDuration) {
        requireOwner(owner);
        requirePositive(leaseDuration);
        LocalDateTime now = LocalDateTime.now();
        return mapper.renewLease(taskId, expectedVersion, owner, now, now.plus(leaseDuration)) == 1;
    }

    @Override
    public boolean succeed(Long taskId, String owner, long expectedVersion, String finalStage) {
        requireOwner(owner);
        return mapper.markSuccess(taskId, expectedVersion, owner, normalizeStage(finalStage),
                LocalDateTime.now()) == 1;
    }

    @Override
    public boolean retry(Long taskId, String owner, long expectedVersion, String stage,
                         Duration delay, String errorCode, String errorMessage) {
        requireOwner(owner);
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        LocalDateTime now = LocalDateTime.now();
        return mapper.markRetryWait(taskId, expectedVersion, owner, normalizeStage(stage), now.plus(delay),
                normalizeError(errorCode), truncate(errorMessage, 2048), now) == 1;
    }

    @Override
    public boolean fail(Long taskId, String owner, long expectedVersion, String stage,
                        String errorCode, String errorMessage) {
        requireOwner(owner);
        return mapper.markTerminal(taskId, expectedVersion, owner, ProcessingTaskState.FAILED.name(),
                normalizeStage(stage), normalizeError(errorCode), truncate(errorMessage, 2048),
                LocalDateTime.now()) == 1;
    }

    private static void requireOwner(String owner) {
        if (!StringUtils.hasText(owner)) {
            throw new IllegalArgumentException("lease owner must not be blank");
        }
    }

    private static void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
    }

    private static String normalizeStage(String stage) {
        return StringUtils.hasText(stage) ? truncate(stage.trim(), 64) : "START";
    }

    private static String normalizeError(String errorCode) {
        return StringUtils.hasText(errorCode) ? truncate(errorCode.trim(), 128) : "UNKNOWN_ERROR";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
