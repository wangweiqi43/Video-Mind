package com.videomind.module.agent.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.module.agent.dto.AdvancedReportResponse;
import com.videomind.module.agent.service.AdvancedReportService;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/videos/{videoId}/advanced-report")
public class AdvancedReportController {

    private final AdvancedReportService service;

    @PostMapping(":ensure")
    public ApiResponse<AdvancedReportResponse> ensure(@PathVariable Long videoId) {
        return ApiResponse.success(service.ensure(videoId, MockUserContext.currentUserId()));
    }

    @GetMapping
    public ApiResponse<AdvancedReportResponse> detail(@PathVariable Long videoId) {
        return ApiResponse.success(service.detail(videoId, MockUserContext.currentUserId()));
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@PathVariable Long videoId) {
        AdvancedReportResponse report = service.detail(videoId, MockUserContext.currentUserId());
        if (report.getReportMarkdown() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=advanced-research-report.md")
                .body(report.getReportMarkdown().getBytes(StandardCharsets.UTF_8));
    }
}
