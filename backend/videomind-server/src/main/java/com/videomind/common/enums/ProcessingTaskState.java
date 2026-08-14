package com.videomind.common.enums;

public enum ProcessingTaskState {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    CANCEL_REQUESTED,
    CANCELLED,
    SUCCESS,
    FAILED,
    DEAD;

    public boolean terminal() {
        return this == CANCELLED || this == SUCCESS || this == FAILED || this == DEAD;
    }
}
