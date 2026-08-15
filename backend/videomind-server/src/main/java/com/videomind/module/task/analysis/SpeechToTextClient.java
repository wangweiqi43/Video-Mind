package com.videomind.module.task.analysis;

import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;

public interface SpeechToTextClient {

    AsrResult transcribe(Long processingTaskId, AudioExtractionResult audio,
                         VideoFile videoFile, TaskRecord taskRecord);
}

