package com.videomind.module.knowledge.timeline;

import com.videomind.module.knowledge.timeline.TimelineFusionService.Timeline;
import java.util.Objects;

/** Canonical fused input shared by timeline persistence, indexing, and summarization. */
public record FusedVideoContent(String title, Timeline timeline, String markdown,
                                int asrSegmentCount, int ocrObservationCount, boolean ocrDegraded) {
    public FusedVideoContent {
        title = Objects.requireNonNullElse(title, "VideoMind 视频");
        timeline = Objects.requireNonNull(timeline, "timeline");
        markdown = Objects.requireNonNullElse(markdown, "");
        if (asrSegmentCount < 0 || ocrObservationCount < 0) {
            throw new IllegalArgumentException("FUSED_CONTENT_COUNT_INVALID");
        }
    }
}
