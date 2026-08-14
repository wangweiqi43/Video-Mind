package com.videomind.module.task.mq;

public record TaskDispatchResult(String eventId, Long processingTaskId, Long businessId, boolean reused) {
}
