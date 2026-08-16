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
    private String rawMarkdownBucket;
    private String rawMarkdownObjectKey;
    private String markdownBucket;
    private String markdownObjectKey;
    private String manifestBucket;
    private String manifestObjectKey;
    private String parser;
    private String mineruTaskId;
    private String processingStage;
    private String visualStatus;
    private Integer imageCount;
    private Integer describedImageCount;
    private String indexStatus;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Integer chunkCount;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
