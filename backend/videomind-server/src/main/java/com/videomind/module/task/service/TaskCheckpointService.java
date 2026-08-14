package com.videomind.module.task.service;

import com.videomind.module.task.entity.TaskCheckpoint;
import java.util.List;

public interface TaskCheckpointService {
    TaskCheckpoint complete(Long taskId, String stage, String artifactJson, String checksum);

    boolean isCompleted(Long taskId, String stage);

    List<TaskCheckpoint> completed(Long taskId);
}
