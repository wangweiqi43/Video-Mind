package com.videomind.module.task.service;

public interface ConsumerInboxService {
    ClaimResult claim(String consumerGroup, String eventId, Long taskId);

    void complete(String consumerGroup, String eventId);

    enum ClaimStatus {
        CLAIMED,
        IN_PROGRESS,
        COMPLETED
    }

    record ClaimResult(ClaimStatus status) {
    }
}
