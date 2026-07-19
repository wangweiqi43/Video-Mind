package com.videomind.module.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.common.exception.BizException;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.module.agent.dto.AgentVideoSyncResponse;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class MindAgentVideoSyncServiceTest {

    private final VideoFileService videos = mock(VideoFileService.class);
    private final AgentClientProperties properties = new AgentClientProperties();
    private final AgentTaskClient client = mock(AgentTaskClient.class);
    private final VideoAgentTaskMapper agentTasks = mock(VideoAgentTaskMapper.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final AgentTaskStateService states = mock(AgentTaskStateService.class);
    private final MindAgentVideoSyncService service = new MindAgentVideoSyncService(
            videos, mock(VideoTranscriptionMapper.class),
            mock(ObjectStorageService.class), client, properties,
            agentTasks, new ObjectMapper(), redisson, states);

    @Test
    void reportsUnsyncedWithoutCreatingTaskWhenTranscriptDoesNotExist() {
        VideoFile video = new VideoFile();
        video.setId(3L);
        video.setTranscriptVersion(0);
        when(videos.getVideoDetail(3L, 8L)).thenReturn(video);

        AgentVideoSyncResponse response = service.status(3L, 8L);

        assertThat(response.getStatus()).isEqualTo("UNSYNCED");
        assertThat(response.getTranscriptVersion()).isZero();
    }

    @Test
    void syncRequiresEnabledIngestAndExistingTranscript() {
        assertThatThrownBy(() -> service.sync(3L, 8L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未启用");

        properties.setEnabled(true);
        properties.setIngestEnabled(true);
        VideoFile video = new VideoFile();
        video.setId(3L);
        video.setTranscriptVersion(0);
        when(videos.getVideoDetail(3L, 8L)).thenReturn(video);
        assertThatThrownBy(() -> service.sync(3L, 8L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未完成转录");
    }

    @Test
    void failedTaskUsesMindAgentRetryAndPersistsNewMapping() throws Exception {
        properties.setEnabled(true);
        properties.setIngestEnabled(true);
        VideoFile video = new VideoFile();
        video.setId(3L);
        video.setUserId(8L);
        video.setTranscriptVersion(2);
        VideoAgentTask failed = new VideoAgentTask();
        failed.setId(10L);
        failed.setVideoId(3L);
        failed.setUserId(8L);
        failed.setSourceTaskId(5L);
        failed.setAgentTaskId("failed-agent-task");
        failed.setTaskType("INGEST");
        failed.setStatus("FAILED");
        failed.setVersion(2);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(videos.getVideoDetail(3L, 8L)).thenReturn(video);
        when(agentTasks.selectList(any())).thenReturn(java.util.List.of(failed));
        when(agentTasks.selectCount(any())).thenReturn(1L);
        when(states.isTerminal("FAILED")).thenReturn(true);
        when(states.normalizeStatus("PENDING")).thenReturn("PENDING");
        when(client.retry(eq("failed-agent-task"), eq(8L), anyString(), eq(null)))
                .thenReturn(new AgentTaskClient.AgentTaskResult("new-agent-task", "PENDING", null, null, null));

        AgentVideoSyncResponse response = service.sync(3L, 8L);

        assertThat(response.getTaskId()).isEqualTo("new-agent-task");
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(client).retry(eq("failed-agent-task"), eq(8L), anyString(), eq(null));
        ArgumentCaptor<VideoAgentTask> inserted = ArgumentCaptor.forClass(VideoAgentTask.class);
        verify(agentTasks).insert(inserted.capture());
        assertThat(inserted.getValue().getAgentTaskId()).isEqualTo("new-agent-task");
        assertThat(inserted.getValue().getVersion()).isEqualTo(2);
    }
}
