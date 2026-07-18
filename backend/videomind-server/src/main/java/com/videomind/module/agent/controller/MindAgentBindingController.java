package com.videomind.module.agent.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.service.MindAgentOAuthService;
import com.videomind.module.agent.service.MindAgentVideoSyncService;
import com.videomind.module.auth.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/mindagent")
public class MindAgentBindingController {

    private final MindAgentOAuthService service;
    private final MindAgentVideoSyncService sync;
    private final AuthProperties auth;

    public MindAgentBindingController(MindAgentOAuthService service, MindAgentVideoSyncService sync, AuthProperties auth) {
        this.service = service;
        this.sync = sync;
        this.auth = auth;
    }

    @GetMapping("/status")
    ApiResponse<?> status() {
        return ApiResponse.success(service.status(MockUserContext.currentUserId()));
    }

    @PostMapping("/authorize")
    ApiResponse<?> authorize() {
        return ApiResponse.success(Map.of("authorizationUrl", service.authorizationUrl(MockUserContext.currentUserId())));
    }

    @GetMapping("/callback")
    void callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response
    ) throws IOException {
        String outcome = "error";
        try {
            if (StringUtils.hasText(error)) {
                service.rejectAuthorization(MockUserContext.currentUserId(), state);
                outcome = "denied";
            } else {
                service.callback(MockUserContext.currentUserId(), code, state);
                outcome = "bound";
            }
        } catch (BizException failure) {
            outcome = failure.getMessage().contains("已过期") ? "expired" : "error";
        }
        String separator = auth.getFrontendUrl().contains("?") ? "&" : "?";
        response.sendRedirect(auth.getFrontendUrl() + separator + "mindagent="
                + URLEncoder.encode(outcome, StandardCharsets.UTF_8));
    }

    @DeleteMapping("/binding")
    ApiResponse<?> unlink() {
        service.unlink(MockUserContext.currentUserId());
        return ApiResponse.success();
    }

    @PostMapping("/videos/{videoId}/sync")
    ApiResponse<?> sync(@PathVariable Long videoId) {
        return ApiResponse.success(sync.sync(videoId, MockUserContext.currentUserId()));
    }
}
