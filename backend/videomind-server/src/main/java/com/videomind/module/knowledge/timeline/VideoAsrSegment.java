package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("video_asr_segment")
public class VideoAsrSegment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long videoId;
    private Long userId;
    private Integer segmentIndex;
    private Long startMs;
    private Long endMs;
    private String text;
    private BigDecimal confidence;
    private LocalDateTime createdTime;
}
