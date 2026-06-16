package com.videomind.module.video.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MultipartUploadInitResponse {

    private String uploadId;
    private List<Integer> uploadedParts;
    private String status;
    private VideoUploadResponse video;
}
