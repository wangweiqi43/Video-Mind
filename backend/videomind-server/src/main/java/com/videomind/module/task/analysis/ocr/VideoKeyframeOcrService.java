package com.videomind.module.task.analysis.ocr;

import com.videomind.config.OcrProperties;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VideoKeyframeOcrService {
    private final KeyframeExtractor extractor;
    private final FrameOcrClient ocrClient;
    private final OcrProperties properties;

    public VideoKeyframeOcrService(KeyframeExtractor extractor, FrameOcrClient ocrClient, OcrProperties properties) {
        this.extractor = extractor;
        this.ocrClient = ocrClient;
        this.properties = properties;
    }

    public List<OcrObservation> recognize(VideoFile videoFile, TaskRecord taskRecord) {
        List<Keyframe> frames = extractor.extract(videoFile, taskRecord);
        List<OcrObservation> observations = new ArrayList<>();
        for (Keyframe frame : frames) {
            try {
                FrameOcrClient.OcrText result = ocrClient.recognize(frame.imagePath());
                if (StringUtils.hasText(result.text())) {
                    observations.add(new OcrObservation(frame.timestampMs(),
                            frame.timestampMs() + properties.getMaxIntervalSeconds() * 1_000L,
                            result.text(), result.confidence()));
                }
            } finally {
                try {
                    Files.deleteIfExists(frame.imagePath());
                } catch (Exception ignored) {
                    // 工作目录将在任务清理阶段再次回收；单帧清理失败不应覆盖 OCR 结果。
                }
            }
        }
        return List.copyOf(observations);
    }
}
