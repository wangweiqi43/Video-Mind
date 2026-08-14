package com.videomind.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private Long userId;
    private String sourceType;
    private String title;
    private String sha256;
    private String dedupeKey;
    private Long currentVersionId;
    private KnowledgeLifecycleStatus status;
    private String failureCode;
    private String failureMessage;
    private Boolean active;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}
