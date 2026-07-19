package com.videomind.module.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import org.junit.jupiter.api.Test;

class AgentTaskStateServiceTest {

    private final VideoAgentTaskMapper tasks = mock(VideoAgentTaskMapper.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final AgentTaskStateService service = new AgentTaskStateService(
            tasks, videos, mock(TaskRecordService.class));

    @Test
    void ingestSuccessUpdatesOnlyAgentFieldsAndNeverOverwritesNormalSummary() throws Exception {
        VideoAgentTask task = task("RUNNING");
        VideoFile video = new VideoFile();
        video.setId(7L);
        video.setUserId(9L);
        video.setSummaryStatus("SUCCESS");
        video.setSummaryVersion(4);
        video.setLatestSummaryId("normal-summary-4");
        when(videos.getVideoDetail(7L, 9L)).thenReturn(video);

        service.applySnapshot(task, new AgentTaskClient.AgentTaskSnapshot(
                "agent-1", "SUCCESS", "DONE", 100, null, null, "kb-7",
                new ObjectMapper().readTree("{\"summary\":\"agent-only summary\",\"knowledgeBaseId\":\"kb-7\"}")));

        assertThat(video.getAgentIngestStatus()).isEqualTo("SUCCESS");
        assertThat(video.getAgentIngestVersion()).isEqualTo(2);
        assertThat(video.getAgentSourceKnowledgeBaseId()).isEqualTo("kb-7");
        assertThat(video.getSummaryStatus()).isEqualTo("SUCCESS");
        assertThat(video.getSummaryVersion()).isEqualTo(4);
        assertThat(video.getLatestSummaryId()).isEqualTo("normal-summary-4");
        verify(tasks).updateById(task);
        verify(videos).updateById(video);
    }

    @Test
    void duplicateTerminalEventIsIdempotent() {
        VideoAgentTask task = task("SUCCESS");

        service.applySnapshot(task, new AgentTaskClient.AgentTaskSnapshot(
                "agent-1", "FAILED", "FAILED", 100, "LATE", "late event", null, null));

        verify(tasks, never()).updateById(task);
        verify(videos, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void researchSuccessPublishesTheFormalReportKnowledgeBase() throws Exception {
        VideoAgentTask task=task("RUNNING");task.setTaskType("RESEARCH");VideoFile video=new VideoFile();video.setId(7L);video.setUserId(9L);video.setAgentSourceKnowledgeBaseId("source-kb");
        when(videos.getVideoDetail(7L,9L)).thenReturn(video);
        service.applySnapshot(task,new AgentTaskClient.AgentTaskSnapshot("research-1","SUCCESS","COMPLETED",100,null,null,null,
                new ObjectMapper().readTree("{\"reportId\":\"report-1\",\"artifactId\":\"artifact-1\",\"reportKnowledgeBaseId\":\"report-kb\",\"reportDocumentId\":\"report-doc\"}")));
        assertThat(task.getReportId()).isEqualTo("report-1");assertThat(video.getAgentReportKnowledgeBaseId()).isEqualTo("report-kb");
        assertThat(video.getAgentSourceKnowledgeBaseId()).isEqualTo("source-kb");verify(videos).updateById(video);
    }

    private VideoAgentTask task(String status) {
        VideoAgentTask task = new VideoAgentTask();
        task.setId(1L);
        task.setVideoId(7L);
        task.setUserId(9L);
        task.setAgentTaskId("agent-1");
        task.setTaskType("INGEST");
        task.setStatus(status);
        task.setVersion(2);
        return task;
    }
}
