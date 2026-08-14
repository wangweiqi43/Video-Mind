package com.videomind.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("document_asset")
public class DocumentAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentVersionId;
    private Integer ordinalNo;
    private String assetType;
    private String mediaType;
    private String bucket;
    private String objectKey;
    private String sourcePath;
    private LocalDateTime createdTime;
}
