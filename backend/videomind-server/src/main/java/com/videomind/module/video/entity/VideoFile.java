package com.videomind.module.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.videomind.common.enums.UploadStatus;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("video_file")
public class VideoFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String originalFilename;
    private String fileMd5;
    private Long fileSize;
    private String contentType;
    private String minioBucket;
    private String minioObjectKey;
    private UploadStatus uploadStatus;
    private Integer durationSeconds;
    private String agentSourceKnowledgeBaseId;
    private String agentReportKnowledgeBaseId;
    private Integer transcriptVersion;
    private Integer agentIngestVersion;
    private String agentIngestStatus;
    private String summaryStatus;
    private Integer summaryVersion;
    private String latestSummaryId;
    private String latestPresentationId;
    private String agentLastError;
    private LocalDateTime agentUpdatedAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
