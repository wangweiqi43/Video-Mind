package com.videomind.module.knowledge.timeline;

import com.videomind.config.OcrProperties;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VideoTimelinePipeline {
    private final TimelineFusionService fusion;
    private final OcrProperties ocr;
    private final VideoTimelineMaterializer materializer;
    private final TimelineKnowledgeIndexer indexer;

    public VideoTimelinePipeline(TimelineFusionService fusion, OcrProperties ocr,
                                 VideoTimelineMaterializer materializer,
                                 TimelineKnowledgeIndexer indexer) {
        this.fusion = fusion;
        this.ocr = ocr;
        this.materializer = materializer;
        this.indexer = indexer;
    }

    public FusedVideoContent fuse(VideoFile video, List<AsrSegment> speech,
                                  List<OcrObservation> visuals, boolean ocrDegraded) {
        List<AsrSegment> safeSpeech = speech == null ? List.of() : List.copyOf(speech);
        List<OcrObservation> safeVisuals = visuals == null ? List.of() : List.copyOf(visuals);
        String title = video.getOriginalFilename() == null ? "VideoMind 视频" : video.getOriginalFilename();
        long durationMs = video.getDurationSeconds() == null ? 0
                : Math.multiplyExact(video.getDurationSeconds().longValue(), 1_000L);
        long maxWindowMs = Math.multiplyExact(ocr.getVisualTailFallbackSeconds(), 1_000L);
        TimelineFusionService.Timeline timeline = fusion.fuse(safeSpeech, safeVisuals, durationMs, maxWindowMs);
        String markdown = fusion.renderMarkdown(timeline, title + " · 时间轴");
        return new FusedVideoContent(title, timeline, markdown, safeSpeech.size(), safeVisuals.size(), ocrDegraded);
    }

    public Optional<TimelineKnowledgeIndexer.IndexedTimeline> materializeAndIndex(TaskRecord task, VideoFile video,
                                                                                   int version,
                                                                                   FusedVideoContent content) {
        if (content.timeline().isEmpty()) {
            log.warn("Skip empty fused timeline, taskId={}, videoId={}",
                    task.getId(), video.getId());
            return Optional.empty();
        }
        VideoTimelineMaterializer.MaterializedTimeline timeline = materializer.materialize(task.getId(), video.getId(),
                task.getUserId(), version, content);
        return Optional.of(indexer.index(task.getUserId(), video.getId(), content.title(), version, timeline));
    }
}
