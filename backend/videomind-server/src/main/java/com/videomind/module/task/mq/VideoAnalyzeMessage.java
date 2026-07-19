package com.videomind.module.task.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoAnalyzeMessage {

    private Long taskId;
    private Long videoId;
    private Long userId;
    private String videoMd5;
    private Boolean autoVectorize;
    private String analysisMode;
}

