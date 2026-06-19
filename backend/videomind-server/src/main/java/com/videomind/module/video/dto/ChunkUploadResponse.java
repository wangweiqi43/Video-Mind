package com.videomind.module.video.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkUploadResponse {

    private String uploadId;
    private Integer partNumber;
    private Boolean uploaded;
    private Boolean skipped;
    private Integer uploadedPartsCount;
    private String chunkMd5;
}
