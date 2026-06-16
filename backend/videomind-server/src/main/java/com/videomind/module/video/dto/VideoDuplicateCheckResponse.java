package com.videomind.module.video.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoDuplicateCheckResponse {

    private Boolean exists;
    private Long videoId;
    private String filename;
    private String fileMd5;
    private String message;
}
