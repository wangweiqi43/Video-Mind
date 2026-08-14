package com.videomind.module.knowledge.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.module.knowledge.dto.KnowledgeBaseCreateRequest;
import com.videomind.module.knowledge.dto.KnowledgeBaseResponse;
import com.videomind.module.knowledge.dto.DocumentUploadResponse;
import com.videomind.module.knowledge.service.DocumentUploadService;
import com.videomind.module.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;
    private final DocumentUploadService documentUploadService;

    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        return ApiResponse.success(service.createUserKnowledgeBase(MockUserContext.currentUserId(), request.name()));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> list() {
        return ApiResponse.success(service.list(MockUserContext.currentUserId()));
    }

    @GetMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBaseResponse> get(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.success(service.get(MockUserContext.currentUserId(), knowledgeBaseId));
    }

    @PostMapping(value = "/{knowledgeBaseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentUploadResponse> upload(@PathVariable Long knowledgeBaseId,
                                                       @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(documentUploadService.upload(
                MockUserContext.currentUserId(), knowledgeBaseId, file));
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public ApiResponse<Void> delete(@PathVariable Long knowledgeBaseId) {
        service.deleteUserKnowledgeBase(MockUserContext.currentUserId(), knowledgeBaseId);
        return ApiResponse.success();
    }
}
