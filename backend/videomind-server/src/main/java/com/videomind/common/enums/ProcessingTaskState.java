package com.videomind.common.enums;

public enum ProcessingTaskState {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SUCCESS,
    FAILED,
    DEAD;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == DEAD;
    }
}
