package com.videomind.module.task.analysis.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.config.FfmpegProperties;
import com.videomind.config.OcrProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FfmpegKeyframeExtractorTest {
    @Test
    void buildsSceneChangeCommandWithoutPeriodicSampling() {
        FfmpegProperties ffmpeg = new FfmpegProperties();
        OcrProperties ocr = new OcrProperties();
        ocr.setSceneThreshold(0.42);
        ocr.setMaxFrames(25);
        var extractor = new FfmpegKeyframeExtractor(null, ffmpeg, ocr);

        var command = extractor.command(Path.of("input.mp4"), Path.of("frame-%06d.jpg"));

        assertThat(command).contains("-fps_mode", "vfr").doesNotContain("-frames:v");
        assertThat(command.get(command.indexOf("-vf") + 1))
                .contains("gt(scene,0.420)", "showinfo")
                .doesNotContain("gte(t-prev_selected_t");
    }

    @Test
    void limitsSceneFramesEvenlyAcrossTheCandidateSequence() {
        List<Keyframe> candidates = java.util.stream.LongStream.rangeClosed(0, 100)
                .mapToObj(second -> new Keyframe(second * 1_000, Path.of("frame-" + second + ".jpg")))
                .toList();

        List<Keyframe> retained = FfmpegKeyframeExtractor.retainEvenly(candidates, 3);

        assertThat(retained).extracting(Keyframe::timestampMs)
                .containsExactly(0L, 50_000L, 100_000L);
    }

    @Test
    void keepsAllSceneFramesWhenTheyFitTheBudget() {
        List<Keyframe> candidates = List.of(
                new Keyframe(4_000, Path.of("frame-2.jpg")),
                new Keyframe(1_000, Path.of("frame-1.jpg")));

        assertThat(FfmpegKeyframeExtractor.retainEvenly(candidates, 3))
                .extracting(Keyframe::timestampMs)
                .containsExactly(1_000L, 4_000L);
    }

    @Test
    void parsesSelectedFrameTimestampsFromShowinfo() {
        assertThat(FfmpegKeyframeExtractor.parseTimestamps("""
                [Parsed_showinfo_1] n:0 pts:0 pts_time:0 duration:1
                [Parsed_showinfo_1] n:1 pts:42 pts_time:4.250 duration:1
                """)).containsExactly(0L, 4_250L);
    }
}
