package com.videomind.module.task.analysis.chunk;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("video_asr_chunk")
public class VideoAsrChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long processingTaskId;
    private Long taskRecordId;
    private Long videoId;
    private Long userId;
    private Integer chunkIndex;
    private Long extractionStartMs;
    private Long extractionEndMs;
    private Long logicalStartMs;
    private Long logicalEndMs;
    private String audioSha256;
    private String engineSignature;
    private VideoAsrChunkState state;
    private String providerTaskId;
    private Integer submitAttempt;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime submittedTime;
    private LocalDateTime completedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
