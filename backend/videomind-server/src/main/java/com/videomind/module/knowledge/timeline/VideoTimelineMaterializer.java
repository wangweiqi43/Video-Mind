package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.knowledge.timeline.TimelineFusionService.Timeline;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VideoTimelineMaterializer {
    private final TimelineFusionService fusion;
    private final VideoAsrSegmentMapper asrMapper;
    private final VideoOcrObservationMapper ocrMapper;
    private final VideoTimelineMapper timelineMapper;
    private final ObjectStorageService storage;
    private final ObjectMapper objectMapper;

    public MaterializedTimeline materialize(Long taskId, Long videoId, Long userId, int version,
                                            String videoTitle, List<AsrSegment> speech,
                                            List<OcrObservation> visuals) {
        persistInputs(taskId, videoId, userId, speech, visuals);
        Timeline timeline = fusion.fuse(speech, visuals);
        if (timeline.events().isEmpty()) {
            throw new IllegalArgumentException("时间轴不能同时缺少有效语音和画面文字");
        }
        String markdown = fusion.renderMarkdown(timeline, videoTitle + " · 时间轴");
        byte[] markdownBytes = markdown.getBytes(StandardCharsets.UTF_8);
        byte[] jsonBytes = json(timeline);
        String prefix = "knowledge/video/" + userId + "/" + videoId + "/timeline/v" + version;
        StoredObject markdownObject = storage.putObject(prefix + "/timeline.md",
                new ByteArrayInputStream(markdownBytes), markdownBytes.length, "text/markdown; charset=utf-8");
        StoredObject jsonObject = storage.putObject(prefix + "/events.json",
                new ByteArrayInputStream(jsonBytes), jsonBytes.length, "application/json; charset=utf-8");
        VideoTimeline record = timelineMapper.selectOne(Wrappers.<VideoTimeline>lambdaQuery()
                .eq(VideoTimeline::getVideoId, videoId)
                .eq(VideoTimeline::getVersionNumber, version)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (record == null) {
            record = new VideoTimeline();
            record.setTaskId(taskId);
            record.setVideoId(videoId);
            record.setUserId(userId);
            record.setVersionNumber(version);
            record.setCreatedTime(now);
        }
        record.setBucket(markdownObject.getBucket());
        record.setMarkdownObjectKey(markdownObject.getObjectKey());
        record.setEventJsonObjectKey(jsonObject.getObjectKey());
        record.setStatus("READY");
        record.setUpdatedTime(now);
        if (record.getId() == null) {
            timelineMapper.insert(record);
        } else {
            timelineMapper.updateById(record);
        }
        return new MaterializedTimeline(record.getId(), timeline, markdown, markdownObject.getBucket(),
                markdownObject.getObjectKey(), jsonObject.getObjectKey());
    }

    private void persistInputs(Long taskId, Long videoId, Long userId, List<AsrSegment> speech,
                               List<OcrObservation> visuals) {
        LocalDateTime now = LocalDateTime.now();
        int index = 0;
        for (AsrSegment source : speech == null ? List.<AsrSegment>of() : speech) {
            VideoAsrSegment value = new VideoAsrSegment();
            value.setTaskId(taskId);
            value.setVideoId(videoId);
            value.setUserId(userId);
            value.setSegmentIndex(index++);
            value.setStartMs(source.startMs());
            value.setEndMs(source.endMs());
            value.setText(source.text());
            value.setConfidence(BigDecimal.valueOf(source.confidence()));
            value.setCreatedTime(now);
            asrMapper.insertIgnore(value);
        }
        index = 0;
        for (OcrObservation source : visuals == null ? List.<OcrObservation>of() : visuals) {
            VideoOcrObservation value = new VideoOcrObservation();
            value.setTaskId(taskId);
            value.setVideoId(videoId);
            value.setUserId(userId);
            value.setObservationIndex(index++);
            value.setStartMs(source.startMs());
            value.setEndMs(source.endMs());
            value.setText(source.text());
            value.setConfidence(BigDecimal.valueOf(source.confidence()));
            value.setCreatedTime(now);
            ocrMapper.insertIgnore(value);
        }
    }

    private byte[] json(Timeline timeline) {
        try {
            return objectMapper.writeValueAsBytes(timeline);
        } catch (Exception failure) {
            throw new IllegalStateException("TIMELINE_JSON_SERIALIZE_FAILED", failure);
        }
    }

    public record MaterializedTimeline(Long timelineId, Timeline timeline, String markdown, String bucket,
                                       String markdownObjectKey, String eventJsonObjectKey) {
    }
}
