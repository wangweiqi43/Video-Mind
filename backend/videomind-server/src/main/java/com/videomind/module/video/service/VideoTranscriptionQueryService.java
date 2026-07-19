package com.videomind.module.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.dto.VideoTranscriptionResponse;
import com.videomind.module.video.entity.VideoFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class VideoTranscriptionQueryService {

    private final VideoFileService videoFileService;
    private final VideoTranscriptionMapper transcriptionMapper;

    public VideoTranscriptionResponse latest(Long videoId, Long userId) {
        VideoFile video = videoFileService.getVideoDetail(videoId, userId);
        VideoTranscription transcription = transcriptionMapper.selectOne(
                new LambdaQueryWrapper<VideoTranscription>()
                        .eq(VideoTranscription::getVideoId, videoId)
                        .eq(VideoTranscription::getUserId, userId)
                        .orderByDesc(VideoTranscription::getUpdatedTime)
                        .orderByDesc(VideoTranscription::getId)
                        .last("LIMIT 1"));
        boolean ready = transcription != null
                && StringUtils.hasText(transcription.getTranscriptionText());
        return VideoTranscriptionResponse.builder()
                .videoId(videoId)
                .transcriptVersion(video.getTranscriptVersion() == null ? 0 : video.getTranscriptVersion())
                .status(ready ? "READY" : "UNAVAILABLE")
                .language(ready ? transcription.getLanguage() : null)
                .transcriptionText(ready ? transcription.getTranscriptionText() : null)
                .updatedTime(ready
                        ? (transcription.getUpdatedTime() == null
                                ? transcription.getCreatedTime()
                                : transcription.getUpdatedTime())
                        : null)
                .build();
    }
}
