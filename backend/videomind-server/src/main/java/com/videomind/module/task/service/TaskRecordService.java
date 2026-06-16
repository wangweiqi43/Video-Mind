package com.videomind.module.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.videomind.module.task.dto.AnalyzeTaskCreateRequest;
import com.videomind.module.task.dto.AnalyzeTaskCreateResponse;
import com.videomind.module.task.entity.TaskRecord;

public interface TaskRecordService extends IService<TaskRecord> {

    AnalyzeTaskCreateResponse createAnalyzeTask(AnalyzeTaskCreateRequest request, Long userId);

    TaskRecord getLatestSuccessfulTaskByVideo(Long videoId, Long userId);

    TaskRecord getTask(Long taskId, Long userId);

    TaskRecord markProcessing(Long taskId, Long userId);

    void markSuccess(Long taskId, Long userId);

    void markFailed(Long taskId, Long userId, String errorMessage);
}
