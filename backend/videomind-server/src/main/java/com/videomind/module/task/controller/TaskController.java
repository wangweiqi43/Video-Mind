package com.videomind.module.task.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.module.task.dto.AnalyzeTaskCreateRequest;
import com.videomind.module.task.dto.AnalyzeTaskCreateResponse;
import com.videomind.module.task.dto.TaskResultResponse;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.task.service.TaskResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRecordService taskRecordService;
    private final TaskResultService taskResultService;

    @PostMapping("/analyze")
    public ApiResponse<AnalyzeTaskCreateResponse> analyze(@Valid @RequestBody AnalyzeTaskCreateRequest request) {
        return ApiResponse.success(taskRecordService.createAnalyzeTask(request, MockUserContext.currentUserId()));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskRecord> detail(@PathVariable Long taskId) {
        return ApiResponse.success(taskRecordService.getTask(taskId, MockUserContext.currentUserId()));
    }

    @GetMapping("/{taskId}/result")
    public ApiResponse<TaskResultResponse> result(@PathVariable Long taskId) {
        return ApiResponse.success(taskResultService.getTaskResult(taskId, MockUserContext.currentUserId()));
    }

    @GetMapping("/video/{videoId}/latest-success")
    public ApiResponse<TaskRecord> latestSuccess(@PathVariable Long videoId,
                                                  @RequestParam(defaultValue = "NORMAL") String applicationMode) {
        return ApiResponse.success(taskRecordService.getLatestSuccessfulTaskByVideo(
                videoId, MockUserContext.currentUserId(), applicationMode));
    }
}
