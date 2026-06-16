package com.videomind.module.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.videomind.common.enums.UploadSessionStatus;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("video_upload_session")
public class VideoUploadSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uploadId;
    private Long userId;
    private String originalFilename;
    private String fileMd5;
    private Long fileSize;
    private String contentType;
    private Integer totalParts;
    private Long chunkSize;
    private Integer uploadedParts;
    private UploadSessionStatus uploadStatus;
    private Long videoId;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    @TableLogic
    private Integer deleted;
}

