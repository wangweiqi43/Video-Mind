package com.videomind.module.task.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.common.exception.BizException;
import com.videomind.module.task.dto.ProcessingTaskStatusResponse;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/processing-tasks")
@RequiredArgsConstructor
public class ProcessingTaskController {
    private final ProcessingTaskMapper tasks;

    @GetMapping("/{taskId}")
    public ApiResponse<ProcessingTaskStatusResponse> get(@PathVariable Long taskId) {
        ProcessingTask task = tasks.selectById(taskId);
        if (task == null || !MockUserContext.currentUserId().equals(task.getUserId())) {
            throw new BizException(404, "任务不存在或无权访问");
        }
        return ApiResponse.success(new ProcessingTaskStatusResponse(task.getId(), task.getTaskType(),
                task.getState(), task.getStage(), task.getErrorCode(), task.getErrorMessage(),
                task.getCreatedTime(), task.getUpdatedTime()));
    }
}
