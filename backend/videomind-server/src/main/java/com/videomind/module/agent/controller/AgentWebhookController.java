package com.videomind.module.agent.controller;

import com.videomind.agentclient.AgentWebhookVerifier;
import com.videomind.common.api.ApiResponse;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.service.AgentWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/webhooks")
public class AgentWebhookController {

    private final AgentWebhookVerifier verifier;
    private final AgentWebhookService service;

    @PostMapping("/task")
    public ApiResponse<Void> task(
            @RequestHeader("X-Timestamp") String timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String body
    ) {
        if (!verifier.verify(timestamp, body, signature)) {
            throw new BizException(401, "Agent Webhook 签名无效或已过期");
        }
        service.handle(body);
        return ApiResponse.success(null);
    }
}
