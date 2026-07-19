package com.videomind.module.video.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoTranscriptionResponse {
    private Long videoId;
    private Integer transcriptVersion;
    private String status;
    private String language;
    private String transcriptionText;
    private LocalDateTime updatedTime;
}
