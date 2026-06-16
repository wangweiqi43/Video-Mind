package com.videomind.module.knowledge.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.module.knowledge.dto.KnowledgeStatusResponse;
import com.videomind.module.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @PostMapping("/vectorize/{taskId}")
    public ApiResponse<KnowledgeStatusResponse> vectorize(@PathVariable Long taskId) {
        return ApiResponse.success(knowledgeService.vectorizeTask(taskId, MockUserContext.currentUserId()));
    }

    @GetMapping("/status/{taskId}")
    public ApiResponse<KnowledgeStatusResponse> status(@PathVariable Long taskId) {
        return ApiResponse.success(knowledgeService.getVectorizeStatus(taskId, MockUserContext.currentUserId()));
    }
}

