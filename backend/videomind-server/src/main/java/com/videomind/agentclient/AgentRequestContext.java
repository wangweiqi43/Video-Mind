package com.videomind.agentclient;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public record AgentRequestContext(String tenantId, Long userId, String idempotencyKey, String traceId) {

    public static AgentRequestContext of(String tenantId, Long userId, String idempotencyKey, String traceId) {
        return new AgentRequestContext(
                tenantId,
                userId,
                idempotencyKey,
                resolveTraceId(traceId)
        );
    }

    private static String resolveTraceId(String traceId) {
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        String current = MDC.get("traceId");
        return StringUtils.hasText(current) ? current : UUID.randomUUID().toString();
    }
}
