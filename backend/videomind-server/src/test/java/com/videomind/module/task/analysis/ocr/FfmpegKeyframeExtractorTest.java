package com.videomind.module.task.analysis.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.config.FfmpegProperties;
import com.videomind.config.OcrProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FfmpegKeyframeExtractorTest {
    @Test
    void buildsSceneChangeCommandWithHeartbeatAndFrameLimit() {
        FfmpegProperties ffmpeg = new FfmpegProperties();
        OcrProperties ocr = new OcrProperties();
        ocr.setSceneThreshold(0.42);
        ocr.setMaxIntervalSeconds(8);
        ocr.setMaxFrames(25);
        var extractor = new FfmpegKeyframeExtractor(null, ffmpeg, ocr);

        var command = extractor.command(Path.of("input.mp4"), Path.of("frame-%06d.jpg"));

        assertThat(command).contains("-fps_mode", "vfr", "-frames:v", "25");
        assertThat(command.get(command.indexOf("-vf") + 1))
                .contains("gt(scene,0.420)", "gte(t-prev_selected_t,8)", "showinfo");
    }

    @Test
    void parsesSelectedFrameTimestampsFromShowinfo() {
        assertThat(FfmpegKeyframeExtractor.parseTimestamps("""
                [Parsed_showinfo_1] n:0 pts:0 pts_time:0 duration:1
                [Parsed_showinfo_1] n:1 pts:42 pts_time:4.250 duration:1
                """)).containsExactly(0L, 4_250L);
    }
}
