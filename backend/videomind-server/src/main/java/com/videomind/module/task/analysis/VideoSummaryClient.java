package com.videomind.module.task.analysis;

import com.videomind.module.knowledge.timeline.FusedVideoContent;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;

public interface VideoSummaryClient {

    SummaryResult summarize(FusedVideoContent content, VideoFile videoFile, TaskRecord taskRecord);
}

