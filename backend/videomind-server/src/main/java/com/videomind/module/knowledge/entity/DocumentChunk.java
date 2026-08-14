package com.videomind.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("document_chunk")
public class DocumentChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String embeddingId;
    private Long userId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long documentVersionId;
    private String sourceType;
    private Integer chunkIndex;
    private Integer parentIndex;
    private Integer childIndex;
    private String heading;
    private String content;
    private String parentContent;
    private Integer startOffset;
    private Integer endOffset;
    private Long startMs;
    private Long endMs;
    private Boolean published;
    private LocalDateTime createdTime;
}
