package com.videomind.module.task.analysis.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videomind.config.OcrProperties;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoKeyframeOcrServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsFrameTimestampToObservationWindowAndRemovesFrame() throws Exception {
        Path image = tempDir.resolve("frame.jpg");
        Files.write(image, new byte[]{1});
        KeyframeExtractor extractor = mock(KeyframeExtractor.class);
        when(extractor.extract(any(), any())).thenReturn(List.of(new Keyframe(4_250, image)));
        FrameOcrClient client = mock(FrameOcrClient.class);
        when(client.recognize(image)).thenReturn(new FrameOcrClient.OcrText("架构总览", 0.93));
        OcrProperties properties = new OcrProperties();
        properties.setMaxIntervalSeconds(8);
        var service = new VideoKeyframeOcrService(extractor, client, properties);

        var observations = service.recognize(new VideoFile(), new TaskRecord());

        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).startMs()).isEqualTo(4_250);
        assertThat(observations.get(0).endMs()).isEqualTo(12_250);
        assertThat(observations.get(0).text()).isEqualTo("架构总览");
        assertThat(image).doesNotExist();
    }
}
