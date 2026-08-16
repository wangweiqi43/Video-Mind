package com.videomind.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("document_upload_idempotency")
public class DocumentUploadIdempotency {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private String idempotencyKey;
    private String requestFingerprint;
    private Long documentId;
    private Long documentVersionId;
    private Long processingTaskId;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
