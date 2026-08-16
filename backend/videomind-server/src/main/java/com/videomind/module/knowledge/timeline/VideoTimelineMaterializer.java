package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.timeline.TimelineFusionService.Timeline;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VideoTimelineMaterializer {
    private final VideoTimelineMapper timelineMapper;
    private final ObjectStorageService storage;
    private final ObjectMapper objectMapper;

    public MaterializedTimeline materialize(Long taskId, Long videoId, Long userId, int version,
                                            FusedVideoContent content) {
        Timeline timeline = content.timeline();
        if (timeline.isEmpty()) {
            throw new IllegalArgumentException("时间轴不能同时缺少有效语音和画面文字");
        }
        String markdown = content.markdown();
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
