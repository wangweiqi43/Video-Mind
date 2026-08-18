package com.videomind.module.video.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.UploadStatus;
import com.videomind.common.exception.BizException;
import com.videomind.config.RateLimitProperties;
import com.videomind.infrastructure.ratelimit.RateLimitService;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.mapper.ConversationSummaryMapper;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.mapper.VideoFileMapper;
import com.videomind.module.video.mapper.VideoUploadSessionMapper;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class VideoFileServiceImplTest {
    @Mock private VideoFileMapper videoFileMapper;
    @Mock private ObjectStorageService objectStorageService;
    @Mock private RateLimitService rateLimitService;
    @Mock private RateLimitProperties rateLimitProperties;
    @Mock private TaskRecordMapper taskRecordMapper;
    @Mock private VideoTranscriptionMapper videoTranscriptionMapper;
    @Mock private AiSummaryResultMapper aiSummaryResultMapper;
    @Mock private VideoUploadSessionMapper videoUploadSessionMapper;
    @Mock private ChatSessionMapper chatSessionMapper;
    @Mock private ChatMessageMapper chatMessageMapper;
    @Mock private ConversationSummaryMapper conversationSummaryMapper;
    @Mock private ConversationContextService conversationContextService;
    @InjectMocks private VideoFileServiceImpl service;

    @BeforeEach
    void setBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", videoFileMapper);
    }

    @Test
    void returnsConcurrentWinnerWhenUniqueInsertIsIgnored() {
        VideoFile candidate = video(7L, "0123456789abcdef0123456789abcdef", null, "candidate.mp4");
        VideoFile winner = video(7L, candidate.getFileMd5(), 19L, "winner.mp4");
        when(videoFileMapper.insertIgnoreUserMd5(candidate)).thenReturn(0);
        when(videoFileMapper.selectOne(any(), anyBoolean())).thenReturn(winner);
        when(objectStorageService.objectExists(winner.getMinioBucket(), winner.getMinioObjectKey())).thenReturn(true);

        var result = service.saveUploadedOrReuse(candidate);

        assertThat(result.reused()).isTrue();
        assertThat(result.video()).isSameAs(winner);
    }

    @Test
    void removesTheLosingMinioObjectWhenOrdinaryUploadsRace() throws Exception {
        byte[] content = "same-video".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String md5 = org.springframework.util.DigestUtils.md5DigestAsHex(content);
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("race.mp4");
        when(file.getInputStream()).thenAnswer(ignored -> new ByteArrayInputStream(content));
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getContentType()).thenReturn("video/mp4");
        when(rateLimitProperties.getUploadPermitsPerMinute()).thenReturn(20L);
        when(objectStorageService.putObject(any(), any(), anyLong(), any()))
                .thenReturn(StoredObject.builder().bucket("videos").objectKey("loser.mp4").build());
        VideoFile winner = video(7L, md5, 23L, "winner.mp4");
        when(videoFileMapper.selectOne(any(), anyBoolean())).thenReturn(null, winner);
        when(videoFileMapper.insertIgnoreUserMd5(any())).thenReturn(0);
        when(objectStorageService.objectExists(winner.getMinioBucket(), winner.getMinioObjectKey())).thenReturn(true);

        assertThatThrownBy(() -> service.upload(file, 7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("videoId=23");
        verify(objectStorageService).removeObject("videos", "loser.mp4");
    }

    private static VideoFile video(Long userId, String md5, Long id, String filename) {
        VideoFile value = new VideoFile();
        value.setId(id);
        value.setUserId(userId);
        value.setOriginalFilename(filename);
        value.setFileMd5(md5);
        value.setFileSize(10L);
        value.setContentType("video/mp4");
        value.setMinioBucket("videos");
        value.setMinioObjectKey(filename);
        value.setUploadStatus(UploadStatus.UPLOADED);
        value.setCreatedTime(LocalDateTime.now());
        value.setUpdatedTime(LocalDateTime.now());
        return value;
    }
}
