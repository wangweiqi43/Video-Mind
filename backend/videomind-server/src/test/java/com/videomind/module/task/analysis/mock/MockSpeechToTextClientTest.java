package com.videomind.module.task.analysis.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import org.junit.jupiter.api.Test;

class MockSpeechToTextClientTest {
    private final MockSpeechToTextClient client = new MockSpeechToTextClient();

    @Test
    void providesTimestampedSegmentsForTheTimelinePipeline() {
        VideoFile video = new VideoFile();
        video.setOriginalFilename("demo.mp4");
        var result = client.transcribe(99L, AudioExtractionResult.builder()
                .audioPath("mock://audio/task-1.wav").durationSeconds(180).build(), video, new TaskRecord());

        assertThat(result.getText()).isNotBlank();
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).startMs()).isZero();
        assertThat(result.getSegments().get(0).endMs()).isEqualTo(180_000);
    }
}
