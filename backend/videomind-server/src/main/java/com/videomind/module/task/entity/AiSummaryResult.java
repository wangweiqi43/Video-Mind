package com.videomind.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_summary_result")
public class AiSummaryResult {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long videoId;
    private Long userId;
    private String summaryText;
    private String summaryJson;
    private String modelName;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}

