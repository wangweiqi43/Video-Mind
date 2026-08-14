package com.videomind.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("processing_task")
public class ProcessingTask {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String eventId;
    private Long userId;
    private ProcessingTaskType taskType;
    private Long businessId;
    private String businessFingerprint;
    private String activeFingerprint;
    private ProcessingTaskState state;
    private String stage;
    private Long stateVersion;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRetryAt;
    private Integer replayGeneration;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
