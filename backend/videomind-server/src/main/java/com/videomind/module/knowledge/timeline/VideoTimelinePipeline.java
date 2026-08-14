package com.videomind.module.knowledge.timeline;

import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.ocr.VideoKeyframeOcrService;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VideoTimelinePipeline {
    private final VideoKeyframeOcrService ocr;
    private final VideoTimelineMaterializer materializer;
    private final TimelineKnowledgeIndexer indexer;

    public VideoTimelinePipeline(VideoKeyframeOcrService ocr, VideoTimelineMaterializer materializer,
                                 TimelineKnowledgeIndexer indexer) {
        this.ocr = ocr;
        this.materializer = materializer;
        this.indexer = indexer;
    }

    public Optional<TimelineKnowledgeIndexer.IndexedTimeline> build(TaskRecord task, VideoFile video,
                                                                     int version, AsrResult asrResult) {
        List<AsrSegment> speech = asrResult.getSegments().stream()
                .map(segment -> new AsrSegment(segment.startMs(), segment.endMs(), segment.text(), 1.0))
                .toList();
        if (speech.isEmpty()) {
            log.warn("Skip timeline because ASR has no sentence timestamps, taskId={}, videoId={}",
                    task.getId(), video.getId());
            return Optional.empty();
        }
        List<OcrObservation> visuals;
        try {
            visuals = ocr.recognize(video, task);
        } catch (RuntimeException exception) {
            visuals = List.of();
            log.warn("Local OCR unavailable; continue with speech-only timeline, taskId={}, videoId={}",
                    task.getId(), video.getId(), exception);
        }
        String title = video.getOriginalFilename() == null ? "VideoMind 视频" : video.getOriginalFilename();
        VideoTimelineMaterializer.MaterializedTimeline timeline = materializer.materialize(task.getId(), video.getId(),
                task.getUserId(), version, title, speech, visuals);
        return Optional.of(indexer.index(task.getUserId(), video.getId(), title, version, timeline));
    }
}
