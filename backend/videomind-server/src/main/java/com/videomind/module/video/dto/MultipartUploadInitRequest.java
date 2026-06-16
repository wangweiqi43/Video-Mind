package com.videomind.module.video.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MultipartUploadInitRequest {

    @NotBlank
    private String filename;

    @NotBlank
    private String fileMd5;

    @NotNull
    @Min(1)
    private Long fileSize;

    private String contentType;

    @NotNull
    @Min(1)
    private Integer totalParts;

    @NotNull
    @Min(1)
    private Long chunkSize;
}

