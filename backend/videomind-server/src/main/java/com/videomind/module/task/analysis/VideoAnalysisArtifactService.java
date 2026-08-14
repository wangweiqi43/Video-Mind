package com.videomind.module.task.analysis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.knowledge.timeline.VideoAsrSegment;
import com.videomind.module.knowledge.timeline.VideoAsrSegmentMapper;
import com.videomind.module.knowledge.timeline.VideoOcrObservation;
import com.videomind.module.knowledge.timeline.VideoOcrObservationMapper;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each durable video-analysis artifact and its version marker in one short DB transaction. */
@Service
@RequiredArgsConstructor
public class VideoAnalysisArtifactService {
    private final VideoTranscriptionMapper transcriptions;
    private final AiSummaryResultMapper summaries;
    private final VideoAsrSegmentMapper asrSegments;
    private final VideoOcrObservationMapper ocrObservations;
    private final VideoFileService videos;

    @Transactional(rollbackFor = Exception.class)
    public int persistAsr(TaskRecord task, VideoFile video, AsrResult asr) {
        VideoTranscription value = transcriptions.selectOne(Wrappers.<VideoTranscription>lambdaQuery()
                .eq(VideoTranscription::getTaskId, task.getId()).last("LIMIT 1"));
        boolean created = value == null;
        LocalDateTime now = LocalDateTime.now();
        if (created) {
            value = new VideoTranscription();
            value.setTaskId(task.getId());
            value.setVideoId(task.getVideoId());
            value.setUserId(task.getUserId());
            value.setCreatedTime(now);
        }
        value.setLanguage(asr.getLanguage());
        value.setTranscriptionText(asr.getText());
        value.setUpdatedTime(now);
        if (created) {
            transcriptions.insert(value);
        } else {
            transcriptions.updateById(value);
        }

        asrSegments.delete(Wrappers.<VideoAsrSegment>lambdaQuery().eq(VideoAsrSegment::getTaskId, task.getId()));
        int index = 0;
        for (AsrSegmentResult source : asr.getSegments()) {
            VideoAsrSegment segment = new VideoAsrSegment();
            segment.setTaskId(task.getId());
            segment.setVideoId(task.getVideoId());
            segment.setUserId(task.getUserId());
            segment.setSegmentIndex(index++);
            segment.setStartMs(source.startMs());
            segment.setEndMs(source.endMs());
            segment.setText(source.text());
            segment.setConfidence(BigDecimal.ONE);
            segment.setCreatedTime(now);
            asrSegments.upsert(segment);
        }

        int current = video.getTranscriptVersion() == null ? 0 : video.getTranscriptVersion();
        int version = created ? current + 1 : Math.max(1, current);
        video.setTranscriptVersion(version);
        video.setSummaryStatus("UNSYNCED");
        video.setSummaryVersion(0);
        video.setLatestSummaryId(null);
        videos.updateById(video);
        return version;
    }

    public AsrResult loadAsr(TaskRecord task) {
        VideoTranscription value = transcriptions.selectOne(Wrappers.<VideoTranscription>lambdaQuery()
                .eq(VideoTranscription::getTaskId, task.getId()).last("LIMIT 1"));
        List<VideoAsrSegment> segments = storedAsr(task);
        if (value == null || segments.isEmpty()) {
            throw new IllegalStateException("ASR_CHECKPOINT_ARTIFACT_MISSING");
        }
        return AsrResult.builder().language(value.getLanguage()).text(value.getTranscriptionText())
                .segments(segments.stream().map(segment -> new AsrSegmentResult(segment.getStartMs(),
                        segment.getEndMs(), segment.getText(), null)).toList()).build();
    }

    public List<AsrSegment> loadSpeech(TaskRecord task) {
        return storedAsr(task).stream().map(segment -> new AsrSegment(segment.getStartMs(), segment.getEndMs(),
                segment.getText(), segment.getConfidence() == null ? 1.0 : segment.getConfidence().doubleValue()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void persistOcr(TaskRecord task, List<OcrObservation> values) {
        ocrObservations.delete(Wrappers.<VideoOcrObservation>lambdaQuery()
                .eq(VideoOcrObservation::getTaskId, task.getId()));
        LocalDateTime now = LocalDateTime.now();
        int index = 0;
        for (OcrObservation source : values) {
            VideoOcrObservation value = new VideoOcrObservation();
            value.setTaskId(task.getId());
            value.setVideoId(task.getVideoId());
            value.setUserId(task.getUserId());
            value.setObservationIndex(index++);
            value.setStartMs(source.startMs());
            value.setEndMs(source.endMs());
            value.setText(source.text());
            value.setConfidence(BigDecimal.valueOf(source.confidence()));
            value.setCreatedTime(now);
            ocrObservations.upsert(value);
        }
    }

    public List<OcrObservation> loadOcr(TaskRecord task) {
        return ocrObservations.selectList(Wrappers.<VideoOcrObservation>lambdaQuery()
                        .eq(VideoOcrObservation::getTaskId, task.getId())
                        .orderByAsc(VideoOcrObservation::getObservationIndex)).stream()
                .sorted(Comparator.comparing(VideoOcrObservation::getObservationIndex))
                .map(value -> new OcrObservation(value.getStartMs(), value.getEndMs(), value.getText(),
                        value.getConfidence() == null ? 1.0 : value.getConfidence().doubleValue()))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public AiSummaryResult saveSummary(TaskRecord task, VideoFile video, int version, SummaryResult result) {
        AiSummaryResult value = summaries.selectOne(Wrappers.<AiSummaryResult>lambdaQuery()
                .eq(AiSummaryResult::getTaskId, task.getId()).last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (value == null) {
            value = new AiSummaryResult();
            value.setTaskId(task.getId());
            value.setVideoId(task.getVideoId());
            value.setUserId(task.getUserId());
            value.setCreatedTime(now);
        }
        value.setSummaryText(result.getSummaryText());
        value.setSummaryJson(result.getSummaryJson());
        value.setModelName(result.getModelName());
        value.setUpdatedTime(now);
        if (value.getId() == null) {
            summaries.insert(value);
        } else {
            summaries.updateById(value);
        }
        video.setSummaryStatus("SUCCESS");
        video.setSummaryVersion(version);
        video.setLatestSummaryId(String.valueOf(value.getId()));
        videos.updateById(video);
        return value;
    }

    private List<VideoAsrSegment> storedAsr(TaskRecord task) {
        return asrSegments.selectList(Wrappers.<VideoAsrSegment>lambdaQuery()
                        .eq(VideoAsrSegment::getTaskId, task.getId()).orderByAsc(VideoAsrSegment::getSegmentIndex))
                .stream().sorted(Comparator.comparing(VideoAsrSegment::getSegmentIndex)).toList();
    }
}
