package com.videomind.module.agent.controller;

import com.videomind.agentclient.AgentWebhookVerifier;
import com.videomind.common.api.ApiResponse;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.service.AgentWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ApiResponse<Void>> task(
            @RequestHeader("X-Timestamp") String timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String body
    ) {
        if (!verifier.verify(timestamp, body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail(401, "Agent Webhook 签名无效或已过期"));
        }
        try {
            service.handle(body);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (BizException failure) {
            int code = failure.getCode() == null ? 500 : failure.getCode();
            HttpStatus status = HttpStatus.resolve(code);
            return ResponseEntity.status(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status)
                    .body(ApiResponse.fail(code, failure.getMessage()));
        } catch (Exception failure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(500, "Agent Webhook 处理失败"));
        }
    }
}
