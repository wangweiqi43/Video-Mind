package com.videomind.module.task.analysis.mock;

import com.videomind.module.task.analysis.AudioExtractorClient;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "videomind.ffmpeg", name = "mode", havingValue = "mock")
public class MockAudioExtractorClient implements AudioExtractorClient {

    @Override
    public AudioExtractionResult extract(VideoFile videoFile, TaskRecord taskRecord) {
        return AudioExtractionResult.builder()
                .audioPath("mock://audio/task-" + taskRecord.getId() + ".wav")
                .durationSeconds(180)
                .audioDurationSeconds(180)
                .build();
    }
}
