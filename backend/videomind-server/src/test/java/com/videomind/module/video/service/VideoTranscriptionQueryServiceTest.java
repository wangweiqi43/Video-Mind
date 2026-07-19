package com.videomind.module.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.dto.VideoTranscriptionResponse;
import com.videomind.module.video.entity.VideoFile;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoTranscriptionQueryServiceTest {

    private final VideoFileService videos = mock(VideoFileService.class);
    private final VideoTranscriptionMapper transcriptions = mock(VideoTranscriptionMapper.class);
    private final VideoTranscriptionQueryService service = new VideoTranscriptionQueryService(videos, transcriptions);

    @BeforeEach
    void setUp() {
        VideoFile video = new VideoFile();
        video.setId(5L);
        video.setUserId(7L);
        video.setTranscriptVersion(2);
        when(videos.getVideoDetail(5L, 7L)).thenReturn(video);
    }

    @Test
    void returnsLatestSharedTranscriptionByVideoInsteadOfAnalysisTask() {
        VideoTranscription transcription = new VideoTranscription();
        transcription.setTaskId(3L);
        transcription.setVideoId(5L);
        transcription.setUserId(7L);
        transcription.setLanguage("zh");
        transcription.setTranscriptionText("共享 ASR 文本");
        transcription.setUpdatedTime(LocalDateTime.of(2026, 7, 19, 20, 0));
        when(transcriptions.selectOne(any())).thenReturn(transcription);

        VideoTranscriptionResponse response = service.latest(5L, 7L);

        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getTranscriptVersion()).isEqualTo(2);
        assertThat(response.getTranscriptionText()).isEqualTo("共享 ASR 文本");
        verify(videos).getVideoDetail(5L, 7L);
    }

    @Test
    void returnsUnavailableWithoutCreatingAnalysisWhenNoTranscriptionExists() {
        when(transcriptions.selectOne(any())).thenReturn(null);

        VideoTranscriptionResponse response = service.latest(5L, 7L);

        assertThat(response.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getTranscriptionText()).isNull();
        assertThat(response.getTranscriptVersion()).isEqualTo(2);
    }
}
