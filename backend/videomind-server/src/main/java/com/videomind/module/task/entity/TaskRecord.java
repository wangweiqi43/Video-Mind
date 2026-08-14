package com.videomind.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.videomind.common.enums.TaskStatus;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("task_record")
public class TaskRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long videoId;
    private String videoMd5;
    private TaskStatus taskStatus;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
