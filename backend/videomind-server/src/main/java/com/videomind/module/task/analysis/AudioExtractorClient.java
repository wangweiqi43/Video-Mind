package com.videomind.module.task.analysis;

import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;

public interface AudioExtractorClient {

    AudioExtractionResult extract(VideoFile videoFile, TaskRecord taskRecord);
}

