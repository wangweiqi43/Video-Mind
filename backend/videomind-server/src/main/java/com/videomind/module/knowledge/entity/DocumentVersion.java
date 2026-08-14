package com.videomind.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("document_version")
public class DocumentVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Integer versionNumber;
    private String originalBucket;
    private String originalObjectKey;
    private Long originalFileSize;
    private String originalContentType;
    private String markdownBucket;
    private String markdownObjectKey;
    private String parser;
    private String mineruTaskId;
    private String processingStage;
    private String indexStatus;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Integer chunkCount;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
