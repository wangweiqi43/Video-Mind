package com.videomind.agentclient;

import lombok.Getter;

@Getter
public class AgentClientException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;
    private final boolean retryable;

    public AgentClientException(String errorCode, int httpStatus, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public AgentClientException(String errorCode, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = 500;
        this.retryable = retryable;
    }
}
