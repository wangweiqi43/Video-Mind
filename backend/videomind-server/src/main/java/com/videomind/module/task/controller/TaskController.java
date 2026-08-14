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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRecordService taskRecordService;
    private final TaskResultService taskResultService;

    @PostMapping("/analyze")
    public ApiResponse<AnalyzeTaskCreateResponse> analyze(@Valid @RequestBody AnalyzeTaskCreateRequest request) {
        long startNanos = System.nanoTime();
        Long userId = MockUserContext.currentUserId();
        try {
            AnalyzeTaskCreateResponse response = taskRecordService.createAnalyzeTask(request, userId);
            log.info("Analyze task submit completed, taskId={}, videoId={}, userId={}, reused={}, status={}, costMs={}",
                    response.getTaskId(), request.getVideoId(), userId, response.getReused(), response.getStatus(),
                    elapsedMs(startNanos));
            return ApiResponse.success(response);
        } catch (RuntimeException ex) {
            log.warn("Analyze task submit failed, videoId={}, userId={}, costMs={}, reason={}",
                    request.getVideoId(), userId, elapsedMs(startNanos), ex.getMessage());
            throw ex;
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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
    public ApiResponse<TaskRecord> latestSuccess(@PathVariable Long videoId) {
        return ApiResponse.success(taskRecordService.getLatestSuccessfulTaskByVideo(videoId,
                MockUserContext.currentUserId()));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskRecord> cancel(@PathVariable Long taskId) {
        return ApiResponse.success(taskRecordService.cancelTask(taskId, MockUserContext.currentUserId()));
    }
}
