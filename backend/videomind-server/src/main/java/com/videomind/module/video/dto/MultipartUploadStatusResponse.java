package com.videomind.module.video.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MultipartUploadStatusResponse {

    private String uploadId;
    private Integer totalParts;
    private Integer uploadedPartsCount;
    private List<Integer> uploadedParts;
    private String status;
    private Long videoId;
}

