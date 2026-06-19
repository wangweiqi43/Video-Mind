package com.videomind.module.video.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoUploadResponse {

    private Long videoId;
    private String filename;
    private String fileMd5;
    private Long fileSize;
    private String bucket;
    private String objectKey;
    private Boolean implemented;
    private Boolean duplicate;
    private Long serverMd5CostMs;
    private Long serverMergeCostMs;
    private Long serverStorageCostMs;
    private Long serverTotalCostMs;
    private String message;
}
