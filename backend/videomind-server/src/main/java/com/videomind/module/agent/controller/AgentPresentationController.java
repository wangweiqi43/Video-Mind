package com.videomind.module.agent.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.module.agent.dto.PresentationCreateRequest;
import com.videomind.module.agent.dto.PresentationTaskResponse;
import com.videomind.module.agent.service.AgentPresentationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/videos/{videoId}/presentations")
public class AgentPresentationController {

    private final AgentPresentationService service;

    @PostMapping
    public ApiResponse<PresentationTaskResponse> create(
            @PathVariable Long videoId,
            @Valid @RequestBody PresentationCreateRequest request
    ) {
        return ApiResponse.success(service.create(videoId, request, MockUserContext.currentUserId()));
    }

    @GetMapping
    public ApiResponse<List<PresentationTaskResponse>> list(@PathVariable Long videoId) {
        return ApiResponse.success(service.list(videoId, MockUserContext.currentUserId()));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<PresentationTaskResponse> detail(@PathVariable Long videoId, @PathVariable Long taskId) {
        return ApiResponse.success(service.detail(videoId, taskId, MockUserContext.currentUserId()));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<PresentationTaskResponse> retry(@PathVariable Long videoId, @PathVariable Long taskId) {
        return ApiResponse.success(service.retry(videoId, taskId, MockUserContext.currentUserId()));
    }
}
