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
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}

