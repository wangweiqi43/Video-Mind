package com.videomind.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("task_checkpoint")
public class TaskCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String stage;
    private String status;
    private String artifactJson;
    private String checksum;
    private LocalDateTime completedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
