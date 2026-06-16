package com.videomind.module.task.analysis;

import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;

public interface VideoSummaryClient {

    SummaryResult summarize(AsrResult asrResult, VideoFile videoFile, TaskRecord taskRecord);
}

