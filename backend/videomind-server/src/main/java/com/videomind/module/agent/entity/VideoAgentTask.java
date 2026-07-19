package com.videomind.module.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("video_agent_task")
public class VideoAgentTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Long userId;
    private Long sourceTaskId;
    private String agentTaskId;
    private String taskType;
    private String status;
    private String stage;
    private Integer progress;
    private String errorCode;
    private String errorMessage;
    private String artifactId;
    private String reportId;
    private String outputUrl;
    private Integer version;
    private String profileVersion;
    private String requestJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
