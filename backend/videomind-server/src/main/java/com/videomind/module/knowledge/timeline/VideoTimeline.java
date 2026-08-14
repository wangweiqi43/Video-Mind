package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("video_timeline")
public class VideoTimeline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long videoId;
    private Long userId;
    private Integer versionNumber;
    private String bucket;
    private String markdownObjectKey;
    private String eventJsonObjectKey;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
