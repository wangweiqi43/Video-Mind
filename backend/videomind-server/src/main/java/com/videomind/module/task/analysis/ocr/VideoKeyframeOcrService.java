package com.videomind.module.task.analysis.ocr;

import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.video.entity.VideoFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class VideoKeyframeOcrService {
    private final KeyframeExtractor extractor;
    private final FrameOcrClient ocrClient;
    private final TaskCancellationGuard cancellation;

    public VideoKeyframeOcrService(KeyframeExtractor extractor, FrameOcrClient ocrClient,
                                   TaskCancellationGuard cancellation) {
        this.extractor = extractor;
        this.ocrClient = ocrClient;
        this.cancellation = cancellation;
    }

    public List<OcrObservation> recognize(VideoFile videoFile, TaskRecord taskRecord) {
        List<Keyframe> frames = extractor.extract(videoFile, taskRecord);
        List<OcrObservation> observations = new ArrayList<>();
        for (Keyframe frame : frames) {
            try {
                cancellation.checkVideoTask(taskRecord.getId());
                FrameOcrClient.OcrText result = ocrClient.recognize(frame.imagePath());
                cancellation.checkVideoTask(taskRecord.getId());
                String text = result == null || result.text() == null ? "" : result.text();
                double confidence = result == null ? 0 : result.confidence();
                observations.add(new OcrObservation(frame.timestampMs(), frame.timestampMs(),
                        text, confidence));
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
