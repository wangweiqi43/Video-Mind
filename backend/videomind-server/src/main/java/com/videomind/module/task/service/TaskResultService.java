package com.videomind.module.task.service;

import com.videomind.module.task.dto.TaskResultResponse;

public interface TaskResultService {

    TaskResultResponse getTaskResult(Long taskId, Long userId);
}

