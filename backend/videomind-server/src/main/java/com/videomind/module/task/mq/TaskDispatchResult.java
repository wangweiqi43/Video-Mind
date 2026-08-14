package com.videomind.module.task.mq;

public record TaskDispatchResult(String eventId, Long taskId, boolean reused) {
}
